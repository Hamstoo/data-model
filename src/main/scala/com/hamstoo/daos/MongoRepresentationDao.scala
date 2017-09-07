package com.hamstoo.daos

import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson.{BSONDocument, BSONElement, Producer}

import scala.collection.breakOut
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MongoRepresentationDao {
  // Mongo `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE = "score"

  // Mongo `text` index weights, `othtext` has implicit weight of 1 (also used as vector weights)
  val CONTENT_WGT = 8
  val KWORDS_WGT = 4
  val LNK_WGT = 10
}

/**
  * Data access object for MongoDB `representations` collection.
  * @param db  Future[DefaultDB] database connection.
  */
class MongoRepresentationDao(db: Future[DefaultDB]) {

  val logger: Logger = Logger(classOf[MongoRepresentationDao])

  import MongoRepresentationDao._
  import com.hamstoo.utils._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}

  private val futCol: Future[BSONCollection] = db map (_ collection "representations")

  /* Ensure that mongo collection has proper `text` index for relevant fields. Note that (apparently) the
   weights must be integers, and if there's any error in how they're specified the index is silently ignored. */
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
      Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
      Index(TIMETHRU -> Ascending :: Nil) % s"bin-$TIMETHRU-1" ::
      Index(LPREF -> Ascending :: USRS -> Ascending :: Nil) % s"bin-$LPREF-1-$USRS-1" ::
      Index(
        key = DTXT -> Text :: OTXT -> Text :: KWORDS -> Text :: LNK -> Text :: Nil,
        options = d :~ "weights" -> (d :~ DTXT -> CONTENT_WGT :~ KWORDS -> KWORDS_WGT :~ LNK -> LNK_WGT)) %
        s"txt-$DTXT-$OTXT-$KWORDS-$LNK" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /**
    * Stores provided representation, optionally updating current state if repr id or link already exists in
    * storage, but not if provided repr id and link belong to different representations. Note that `id` and `link`
    * are never updated and returned id may initially belong to existing repr.
    * Returns a future id of either updated or inserted repr.
    */
  def save(repr: Representation): Future[String] = for {
    c <- futCol

    /* Check if id and (in public repr) link exist in the db, failing on conflict. */
    optReprById <- retrieveById(repr.id)

    optReprByUrl <- if (repr.link.isEmpty || optReprById.exists(_.link == repr.link) || repr.users.nonEmpty)
      Future.successful(None) else
      retrieveByUrl(repr.link.get)

    optRepr <- if (optReprById.nonEmpty && optReprByUrl.nonEmpty)
      Future.failed(new Exception("Id and link for different reprs")) else
      Future.successful(optReprById orElse optReprByUrl)

    /* Insert new repr either as is or as an update, conserving id and link in the latter case. */
    id = optRepr map (_.id) getOrElse repr.id

    wr <- optRepr match {
      case Some(r) if r.users != repr.users =>
        logger.warn(s"Unable to insert new Representation; user IDs conflict: ${r.users} != ${repr.users}")
        Future.failed(new Exception("Can't update, user IDs conflict"))

      case Some(r) if r.link != repr.link =>
        logger.warn(s"Unable to insert new Representation; URLs do not match: ${r.link} != ${repr.link}")
        Future.failed(new Exception("Can't update, URLs do not match"))

      case Some(r) =>
        // TODO: can check here whether vectors are sufficiently different to require update
        logger.info(s"Updating (yet still ignoring vector similarity!) existing Representation(id=${r.id}, link=${r.link}, timeFrom=${r.timeFrom}, dt=${r.timeFrom.dt})")
        val now = DateTime.now.getMillis
        for {
          wr <- c update(d :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now)) // retire the old one
          _ <- wr failIfError;
          wr <- c insert repr.copy(id = id, timeFrom = now, timeThru = INF_TIME) // insert the new one
        } yield wr

      case _ =>
        logger.info(s"Inserting new Representation(id=${repr.id}, link=${repr.link})")
        c insert repr
    }
    _ <- wr failIfError
  } yield id

  /** Retrieves a current (latest) representation by id. */
  def retrieveById(id: String): Future[Option[Representation]] = for {
    c <- futCol
    optRep <- c.find(d :~ ID -> id :~ curnt).one[Representation]
  } yield optRep

  /** Retrieves a current (latest) public representation by URL. */
  def retrieveByUrl(url: String): Future[Option[Representation]] = for {
    c <- futCol
    seq <- (c find d :~ LPREF -> url.prefx :~ curnt :~ USRS -> (d :~ "$size" -> 0)).coll[Representation, Seq]() /*
    The {users:{$size:0}} part of the query is not indexable and thus goes last. */
  } yield seq find (_.link contains url)

  /** Retrieves all representations, including private and previous versions, by URL. */
  def retrieveAllByUrl(url: String): Future[Seq[Representation]] = for {
    c <- futCol
    seq <- (c find d :~ LPREF -> url.prefx).coll[Representation, Seq]()
  } yield seq filter (_.link contains url)

  /** Retrieves all representations, including private and previous versions, by Id. */
  def retrieveAllById(id: String): Future[Seq[Representation]] = for {
    c <- futCol
    seq <- c.find(d :~ ID -> id).coll[Representation, Seq]()
  } yield seq

  /**
    * Given a set of Representation IDs and a query string, return a mapping from ID to
    * `doctext`/`CONTENT` and vector representation (if one has been generated yet). Also
    * returns a matching score (and in descending order of this score) as computed by Mongo
    * and described here: https://docs.mongodb.com/manual/core/index-text/#specify-weights.
    *
    * SELECT id, doctext, vec, textScore() AS score FROM tbRepresentation
    * WHERE ANY(SPLIT(doctext) IN @query)
    * --ORDER BY score DESC -- actually this is not happening, would require `.sort` after `.find`
    */
  def search(ids: Set[String], query: String):
          Future[Map[String, (Option[Long], String, Option[Map[String, Vec]], Double)]] = for {
    c <- futCol
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt :~ "$text" -> (d :~ "$search" -> query)
    pjn = d :~ ID -> 1 :~ N_WORDS -> 1 :~ DTXT -> 1 :~ VECS -> 1 :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel projection pjn).coll[BSONDocument, Seq]()
  } yield seq.map { doc =>
    doc.getAs[String](ID).get -> (
      doc.getAs[Long](N_WORDS),
      doc.getAs[String](DTXT).get,
      doc.getAs[Map[String, Vec]](VECS),
      doc.getAs[Double](SCORE).get)
  }(breakOut[
    Seq[BSONDocument],
    (String, (Option[Long], String, Option[Map[String, Vec]], Double)),
    Map[String, (Option[Long], String, Option[Map[String, Vec]], Double)]])
}
