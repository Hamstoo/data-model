package com.hamstoo.daos

import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import org.joda.time.DateTime
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

class MongoRepresentationDao(db: Future[DefaultDB]) {

  import MongoRepresentationDao._
  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, ExtendedString, ExtendedWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "representations")
  private val d = BSONDocument.empty
  private val curnt: Producer[BSONElement] = TIMETHRU -> Long.MaxValue

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
    * Stores provided representation, optionally updating current state if repr id or link already exists in storage,
    * but not if provided repr id and link belong to different representations. Note that `id` and `link` are never
    * updated and returned id may initially belong to existing repr.
    * Returns a future id of either updated or inserted repr.
    */
  def save(repr: Representation): Future[String] = for {
    c <- futCol
    /* Check if id and link exist in the db, failing on conflict. */
    optRepr0 <- (c find d :~ ID -> repr.id :~ curnt).one[Representation]
    optRepr1 <- if (repr.link.isEmpty || optRepr0.flatMap(_.link) == repr.link)
      Future successful None else retrieveByUrl(repr.link.get)
    optRepr <- if (optRepr0.nonEmpty && optRepr1.nonEmpty)
      Future failed new Exception("Id and link for different reprs.") else Future.successful(optRepr0 orElse optRepr1)
    /* Insert new repr either as is or as an update, conserving id and link in the latter case. */
    id = optRepr map (_.id) getOrElse repr.id
    wr <- optRepr match {
      case Some(r) =>
        val now = DateTime.now.getMillis
        for {
          wr <- c update(d :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now))
          wr <- wr.ifOk(c insert repr.copy(id = id, link = r.link, timeFrom = now, timeThru = Long.MaxValue))
        } yield wr
      case _ => c insert repr
    }
    _ <- wr failIfError
  } yield id

  /** Retrieves a current representation by id. */
  def retrieveById(id: String): Future[Option[Representation]] = for {
    c <- futCol
    optRep <- c.find(d :~ ID -> id :~ curnt).one[Representation]
  } yield optRep

  /** Retrieves a public representation by URL. */
  def retrieveByUrl(url: String): Future[Option[Representation]] = for {
    c <- futCol
    seq <- (c find d :~ LPREF -> url.prefx :~ curnt :~ USRS -> (d :~ "$size" -> 0)).coll[Representation, Seq]() /*
    The {users:{$size:0}} part of the query is not indexable and thus goes last. */
  } yield seq find (_.link contains url)

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
  def search(ids: Set[String], query: String): Future[Map[String, (String, Option[Map[String, Vec]], Double)]] = for {
    c <- futCol
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt :~ "$text" -> (d :~ "$search" -> query)
    pjn = d :~ ID -> 1 :~ DTXT -> 1 :~ VECS -> 1 :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel projection pjn).coll[BSONDocument, Seq]()
  } yield seq.map { doc =>
    doc.getAs[String](ID).get -> (
      doc.getAs[String](DTXT).get,
      doc.getAs[Map[String, Vec]](VECS),
      doc.getAs[Double](SCORE).get)
  }(breakOut[
    Seq[BSONDocument],
    (String, (String, Option[Map[String, Vec]], Double)),
    Map[String, (String, Option[Map[String, Vec]], Double)]])
}
