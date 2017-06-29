package com.hamstoo.daos

import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson.BSONDocument

import scala.collection.breakOut
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoRepresentationDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, StrWithBinaryPrefix, digestWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "representations")
  private val d = BSONDocument.empty
  // `text` index search score <projectedFieldName>, not a field name of the collection
  private val SCORE = "score"
  private val CONTENT_WEIGHT = 8

  // Ensure that mongo collection has proper `text` index for relevant fields.  Note that (apparently) the
  // weights must be integers, and if there's any error in how they're specified the index is silently ignored.
  private val indxs: Map[String, Index] = Index(
    key = DTXT -> Text :: OTXT -> Text :: KWORDS -> Text :: LNK -> Text :: Nil,
    options = d :~ "weights" -> (d :~ DTXT -> CONTENT_WEIGHT :~ KWORDS -> 4 :~ LNK -> 10)) %
    s"txt-$DTXT-$OTXT-$KWORDS-$LNK" ::
    Index(LPREF -> Ascending :: Nil) % s"bin-$LPREF-1" ::
    Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /** */
  def save(repr: Representation): Future[Either[String, String]] = for {
    c <- futCol
    wr <- c update(d :~ LNK -> repr.link, repr, upsert = true)
  } yield digestWriteResult(wr, repr._id)

  /** Retrieves a `Representation` by id. */
  def retrieveId(id: String): Future[Option[Representation]] = for {
    c <- futCol
    optRep <- c.find(d :~ ID -> id).one[Representation]
  } yield optRep

  /** Retrieves a `Representation` by URL. */
  def retrieveUrl(url: String): Future[Option[String]] = for {
    c <- futCol
    seq <- (c find d :~ LPREF -> url.prefx projection d :~ LNK -> 1).coll[BSONDocument, Seq]()
  } yield seq collectFirst { case doc if doc.getAs[String](LNK).get == url => doc.getAs[String](ID).get }

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
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ "$text" -> (d :~ "$search" -> query)
    pjn = d :~ DTXT -> 1 :~ VECR -> 1 :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel projection pjn).coll[BSONDocument, Seq]()
  } yield seq.map { doc =>
    doc.getAs[String](ID).get -> (doc.getAs[String](DTXT).get, doc.getAs[Vec](VECR), doc.getAs[Double](SCORE).get)
  }(breakOut[Seq[BSONDocument], (String, (String, Option[Vec], Double)), Map[String, (String, Option[Vec], Double)]])
}
