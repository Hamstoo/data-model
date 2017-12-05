package com.hamstoo.daos

import com.hamstoo.models.Representation._
import com.hamstoo.models.{Page, Representation}
import org.joda.time.DateTime
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
class MongoRepresentationDao(db: () => Future[DefaultDB]) {

  import MongoRepresentationDao._
  import com.hamstoo.models.Mark.{SCORE, TIMEFROM, TIMETHRU}
  import com.hamstoo.utils._

  val logger: Logger = Logger(classOf[MongoRepresentationDao])

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "representations")

  // data migration
  case class WeeRepr(id: String, timeFrom: Long, page: String)
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

  // Ensure that mongo collection has proper `text` index for relevant fields. Note that (apparently) the
  // weights must be integers, and if there's any error in how they're specified the index is silently ignored.
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Index(TIMETHRU -> Ascending :: Nil) % s"bin-$TIMETHRU-1" ::
    Index(LPREFX -> Ascending :: Nil) % s"bin-$LPREFX-1" ::
    Index(
      key = DTXT -> Text :: OTXT -> Text :: KWORDS -> Text :: LNK -> Text :: Nil,
      options = d :~ "weights" -> (d :~ DTXT -> CONTENT_WGT :~ KWORDS -> KWORDS_WGT :~ LNK -> LNK_WGT)) %
      s"txt-$DTXT-$OTXT-$KWORDS-$LNK" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 393 seconds)

  /**
    * Inserting representation to database
    * @param repr - representation
    * @return - inserted representation
    */
  def insert(repr: Representation): Future[Representation] = {

    val reprId = repr.id // for logging only
    logger.debug(s"Inserting new Representation(id=${repr.id}, link=${repr.link})")

    for {
      c <- dbColl()
      wr <- c.insert(repr)
      _ <- wr.failIfError
    } yield {
      logger.info(s"Representation with id: $reprId was successfully inserted")
      repr
    }
  }

  /**
    * Update representation
    * @param repr - new representation
    * @param now - time
    * @return - updated representation
    */
  def update(repr: Representation, now: Long = DateTime.now().getMillis): Future[Representation] = {
    val reprId = repr.id
    logger.info(s"Updating existing Representation(id=$reprId, link=${repr.link}, timeFrom=${repr.timeFrom}, dt=${repr.timeFrom.dt})")

    for {
      c <- dbColl()
      wr <- c.update(d :~ ID -> reprId :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now)) // retire the old one
      _ <- wr failIfError; // semicolon wouldn't be necessary if used `wr.failIfError` (w/ the dot) instead--weird
      updatedRepr <- insert(repr.copy(timeFrom = now))
    } yield {
      logger.info(s"Representation with id: $reprId was successfully updated")
      updatedRepr
    }
  }

  /**
    * Stores provided representation, optionally updating current state if repr ID already exists in database.
    * @return  Returns a `Future` repr ID of either updated or inserted repr.
    */
  def save(repr: Representation, now: Long = DateTime.now.getMillis): Future[String] = {
    val reprId = repr.id

    retrieve(reprId).flatMap {
      case Some(_) => update(repr, now = now)
      case _       => insert(repr.copy(timeFrom = now))
    } map(_.id)
  }

  /** Retrieves a current (latest) representation by ID. */
  def retrieve(id: String): Future[Option[Representation]] = for {
    c <- dbColl()
    optRepr <- c.find(d :~ ID -> id :~ curnt).one[Representation]
  } yield optRepr

  /** Retrieves all representations, including previous versions, by ID. */
  def retrieveAll(id: String): Future[Seq[Representation]] = for {
    c <- dbColl()
    seq <- c.find(d :~ ID -> id).coll[Representation, Seq]()
  } yield seq

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
  def search(ids: Set[String], query: String): Future[Map[String, Representation]] = for {
    c <- dbColl()
    _ = logger.debug(s"Searching with query '$query' for reprs (first 5): ${ids.take(5)}")
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt

    // exclude these fields from the returned results to conserve memory during search
    searchExcludedFields = d :~ (PAGE -> 0) :~ (OTXT -> 0) :~ (LPREFX -> 0) :~ (HEADR -> 0) :~ (KWORDS -> 0)

    // this projection doesn't have any effect without this selection
    searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
    searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

    _ = logger.debug(BSONDocument.pretty(sel :~ searchScoreSelection))
    seq <- c.find(sel :~ searchScoreSelection,
                  searchExcludedFields :~ searchScoreProjection)/*.sort(searchScoreProjection)*/
      .coll[Representation, Seq]()

  } yield seq.map { repr =>
    repr.id -> repr

  }(breakOut[
    Seq[Representation],
    (String, Representation),
    Map[String, Representation]])
}
