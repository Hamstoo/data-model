package com.hamstoo.daos

import com.hamstoo.models.Representation._
import com.hamstoo.models.{Page, Representation, RSearchable}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson.{BSONDocument, BSONDocumentHandler, Macros}

import scala.collection.breakOut
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


object MongoRepresentationDao {

  // Mongo `text` index weights, `othtext` has implicit weight of 1 (also used as vector weights)
  val CONTENT_WGT = 8
  val KWORDS_WGT = 4
  val LNK_WGT = 10
}

/**
  * Data access object for MongoDB `representations` collection.
  * @param db  Future[DefaultDB] database connection returning function.
  */
class MongoRepresentationDao(db: () => Future[DefaultDB])
    extends MongoReprEngineProductDao[Representation]("representation", db) {

  import MongoRepresentationDao._
  import com.hamstoo.models.Mark.{ID, SCORE, TIMEFROM, TIMETHRU}
  import com.hamstoo.utils._

  val logger: Logger = Logger(classOf[MongoRepresentationDao])

  override def dbColl(): Future[BSONCollection] = db().map(_ collection "representations")

  // data migration
  case class WeeRepr(id: String, timeFrom: Long, page: String)
  if (scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)) {
    Await.result(for {
      c <- dbColl()
      _ = logger.info(s"Performing data migration for `representations` collection")

      // ensure every repr has a page String before changing them all to Pages
      _ <- c.update(d :~ PAGE -> (d :~ "$exists" -> 0), d :~ "$set" -> (d :~ PAGE -> ""), multi = true)

      // change any remaining String `Representation.page`s to Pages (version 0.9.17)
      sel0 = d :~ PAGE -> (d :~ "$type" -> 2)
      pjn0 = d :~ ID -> 1 :~ TIMEFROM -> 1 :~ PAGE -> 1
      stringPaged <- {
        implicit val r: BSONDocumentHandler[WeeRepr] = Macros.handler[WeeRepr]
        c.find(sel0, pjn0).coll[WeeRepr, Seq]()
      }
      _ = logger.info(s"Updating ${stringPaged.size} `Representations.page`s from Strings to Pages")
      _ <- Future.sequence { stringPaged.map { r =>
        val pg = Page(MediaType.TEXT_HTML.toString, r.page.getBytes)
        c.update(d :~ ID -> r.id :~ TIMEFROM -> r.timeFrom, d :~ "$set" -> (d :~ PAGE -> pg), multi = true)
      }}

      // reduce size of existing `lprefx`s down to URL_PREFIX_LENGTH to be consistent with MongoMarksDao (version 0.9.16)
      sel1 = d :~ "$where" -> s"Object.bsonsize({$LPREFX:this.$LPREFX})>$URL_PREFIX_LENGTH+19"
      longPfxed <- c.find(sel1).coll[Representation, Seq]()
      _ = logger.info(s"Updating ${longPfxed.size} `Representation.lprefx`s to length $URL_PREFIX_LENGTH bytes")
      _ <- Future.sequence { longPfxed.map { repr => // lprefx will have been overwritten upon construction
        c.update(d :~ ID -> repr.id :~ TIMEFROM -> repr.timeFrom, d :~ "$set" -> (d :~ LPREFX -> repr.lprefx), multi = true)
      }}
    } yield (), 606 seconds)
  } else logger.info(s"Skipping data migration for `representations` collection")

  // Ensure that mongo collection has proper `text` index for relevant fields. Note that (apparently) the
  // weights must be integers, and if there's any error in how they're specified the index is silently ignored.
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Index(TIMETHRU -> Ascending :: Nil) % s"bin-$TIMETHRU-1" ::
    Index(LPREFX -> Ascending :: Nil) % s"bin-$LPREFX-1" ::
    // Text Index search before this index was changed into a Compound Index (with the addition of ID and TIMETHRU
    // fields) was performing an IXSCAN over the entire `representations` collection for each query word in a
    // stage:TEXT_OR query.  Compound Indexes that include Text Index keys are allowed with caveats, see comment below.
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: DTXT -> Text :: OTXT -> Text :: KWORDS -> Text :: LNK -> Text ::
      Nil, options = d :~ "weights" -> (d :~ DTXT -> CONTENT_WGT :~ KWORDS -> KWORDS_WGT :~ LNK -> LNK_WGT)) %
      s"bin-$ID-1-$TIMETHRU-1--txt-$DTXT-$OTXT-$KWORDS-$LNK" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 393 seconds)

  /**
    * Given a set of Representation IDs and a query string, return a mapping from ID to
    * Representation instances. Also
    * returns a matching score (and in descending order of this score) as computed by Mongo
    * and described here: https://docs.mongodb.com/manual/core/index-text/#specify-weights.
    *
    * SELECT id, doctext, vec, textScore() AS score FROM tbRepresentation
    * WHERE ANY(SPLIT(doctext) IN @query)
    * --ORDER BY score DESC -- actually this is not happening, would require '.sort' after '.find'
    */
  def search(ids: Set[String], query: String): Future[Map[String, RSearchable]] = for {
    c <- dbColl()
    _ = logger.info(s"Searching with query '$query' for reprs (first 5 out of ${ids.size}): ${ids.take(5)}")

    // this Future.sequence the only way I can think of to lookup documents via their IDs prior to a Text Index search
    seq <- Future.sequence { ids.map { id =>

      // "If the compound text index includes keys preceding the text index key, to perform a $text search, the query
      // predicate must include *equality* (FWC - $in doesn't count) match conditions on the preceding keys."
      //   https://docs.mongodb.com/v3.2/core/index-text/
      val sel = d :~ ID -> id :~ curnt

      // this projection doesn't have any effect without this selection
      val searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
      val searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

      logger.debug(BSONDocument.pretty(sel :~ searchScoreSelection))
      c.find(sel :~ searchScoreSelection, searchScoreProjection).one[RSearchable]
    }}
  } yield seq.flatten.map { repr => repr.id -> repr }.toMap
}
