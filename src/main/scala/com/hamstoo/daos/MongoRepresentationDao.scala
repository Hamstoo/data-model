package com.hamstoo.daos

import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson.{BSONDocument, BSONElement, BSONObjectID, Producer}

import scala.collection.breakOut
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoRepresentationDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, StrWithBinaryPrefix, digestWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "representations")
  private val d = BSONDocument.empty
  private val curnt: Producer[BSONElement] = CURRNT -> Long.MaxValue
  // `text` index search score <projectedFieldName>, not a field name of the collection
  private val SCORE = "score"
  private val CONTENT_WEIGHT = 8

  /* Data migration to 0.7.1 that adds `lprefx` field to documents which should have it. */
  for {
    c <- futCol
    sel = d :~ LNK -> (d :~ "$exists" -> true) :~ LPREF -> (d :~ "$exist" -> false)
    n <- c count Some(sel)
    if n > 0
  } for {seq <- (c find sel).coll[BSONDocument, Seq]()} for {
    r <- seq
    upd = d :~ "$set" -> (d :~ LPREF -> r.getAs[String](LNK).get.prefx)
  } c update(d :~ "_id" -> r.getAs[BSONObjectID]("_id").get, upd)

  /* Data migration to 0.8.0 that adds the fields for 'from-thru' model. */
  for {
    c <- futCol
    sel = d :~ CURRNT -> (d :~ "$exists" -> false)
    n <- c count Some(sel)
    if n > 0
  } for {seq <- (c find sel).coll[BSONDocument, Seq]()} for {
    r <- seq
    id = r.getAs[BSONObjectID]("_id").get
    upd = d :~ "$set" -> (d :~ ID -> id :~ TSTAMP -> r.getAs[Long]("timestamp").get :~ curnt) :~
      "$unset" -> (d :~ "timestamp" -> 1)
  } c update(d :~ "_id" -> id, upd)

  /* Ensure that mongo collection has proper `text` index for relevant fields.  Note that (apparently) the
   weights must be integers, and if there's any error in how they're specified the index is silently ignored. */
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TSTAMP -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TSTAMP-1-uniq" ::
      Index(ID -> Ascending :: CURRNT -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$CURRNT-1-uniq" ::
      Index(CURRNT -> Ascending :: Nil) % s"bin-$CURRNT-1" ::
      Index(LPREF -> Ascending :: Nil) % s"bin-$LPREF-1" ::
      Index(
        key = DTXT -> Text :: OTXT -> Text :: KWORDS -> Text :: LNK -> Text :: Nil,
        options = d :~ "weights" -> (d :~ DTXT -> CONTENT_WEIGHT :~ KWORDS -> 4 :~ LNK -> 10)) %
        s"txt-$DTXT-$OTXT-$KWORDS-$LNK" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /**
    * Stores provided representation, optionally updating current state if repr id or link already exists in storage,
    * but not if provided repr id and link belong to different representations. Note that `id` and `link` are never
    * updated and returned id may initially belong to existing repr.
    */
  def save(repr: Representation): Future[Either[String, String]] = for {
    c <- futCol
    /* Check if id and link exist in the db, failing on conflict. */
    optRepr0 <- (c find d :~ ID -> repr.id :~ curnt).one[Representation]
    optRepr1 <- if (repr.link.isEmpty || optRepr0.flatMap(_.link) == repr.link)
      Future successful None else retrieveUrl(repr.link.get)
    optRepr <- if (optRepr0.nonEmpty && optRepr1.nonEmpty)
      Future failed new Exception("Id and link for different reprs.") else Future.successful(optRepr0 orElse optRepr1)
    /* Insert new repr either as is or as an update, conserving id and link in the latter case. */
    id = optRepr map (_.id) getOrElse repr.id
    wr <- optRepr match {
      case None => c insert repr
      case Some(r) =>
        val now = DateTime.now.getMillis
        c update(d :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ CURRNT -> now))
        c insert repr.copy(id = id, link = r.link, from = now, thru = Long.MaxValue)
    }
  } yield digestWriteResult(wr, id)

  /** Retrieves a current representation by id. */
  def retrieveId(id: String): Future[Option[Representation]] = for {
    c <- futCol
    optRep <- c.find(d :~ ID -> id :~ curnt).one[Representation]
  } yield optRep

  /** Retrieves a current representation by URL. */
  def retrieveUrl(url: String): Future[Option[Representation]] = for {
    c <- futCol
    seq <- (c find d :~ LPREF -> url.prefx :~ curnt).coll[Representation, Seq]()
  } yield seq collectFirst { case rep if rep.link contains url => rep }

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
  def search(ids: Set[String], query: String): Future[Map[String, (String, Option[Seq[Double]], Double)]] = for {
    c <- futCol
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt :~ "$text" -> (d :~ "$search" -> query)
    pjn = d :~ ID -> 1 :~ DTXT -> 1 :~ VECR -> 1 :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel projection pjn).coll[BSONDocument, Seq]()
  } yield seq.map { doc =>
    doc.getAs[String](ID).get -> (doc.getAs[String](DTXT).get, doc.getAs[Vec](VECR), doc.getAs[Double](SCORE).get)
  }(breakOut[Seq[BSONDocument], (String, (String, Option[Vec], Double)), Map[String, (String, Option[Vec], Double)]])
}
