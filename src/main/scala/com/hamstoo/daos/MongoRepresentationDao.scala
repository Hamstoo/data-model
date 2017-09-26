package com.hamstoo.daos

import com.hamstoo.models.{Page, Representation}
import com.hamstoo.models.Representation._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson.{BSONDocument, BSONDocumentHandler, BSONElement, BSONLong, BSONString, Macros, Producer}

import scala.collection.{breakOut, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


object MongoRepresentationDao {

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
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU, SCORE}

  private val futColl: Future[BSONCollection] = db map (_ collection "representations")

  // trying this out for fun (debugging)
  implicit class StringBSONFunctions(private val key: String) /*extends AnyVal*/ {
    //def :>(elements: Producer[BSONElement]*): BSONDocument = d :~ (Producer[BSONElement](key) :+ elements)
    //def :>(value: String): BSONDocument = d :~ Producer[BSONElement](key -> value)
    def :>(value: String): BSONDocument = d :~ key -> value
  }

  // data migration
  case class WeeRepr(id: String, timeFrom: Long, page: String)
  Await.result( for {
    c <- futColl

    // change any remaining String `Representation.page`s to Pages (version 0.9.17)
    sel0 = d :~ PAGE -> ("$type" :> "string")
    pjn0 = d :~ ID -> "" :~ TIMEFROM -> BSONLong(1) :~ PAGE -> ""
    stringPaged <- {
      implicit val r: BSONDocumentHandler[WeeRepr] = Macros.handler[WeeRepr]
      c.find(sel0, pjn0).coll[WeeRepr, Seq]()
    }
    _ = logger.info(s"Updating ${stringPaged.size} `Representations.page`s from Strings to Pages")
    _ <- Future.sequence { stringPaged.map { r =>
      val pg = Page(MediaType.TEXT_HTML.toString, r.page.getBytes)
      c.update(d :~ ID -> r.id :~ TIMEFROM -> r.timeFrom, d :~ "$set" -> (d :~ PAGE -> pg))
    }}

    // reduce size of existing `lprefx`s down to URL_PREFIX_LENGTH to be consistent with MongoMarksDao (version 0.9.16)
    sel1 = d :~ "$where" -> s"Object.bsonsize({$LPREFX:this.$LPREFX})>$URL_PREFIX_LENGTH+19"
    longPfxed <- c.find(sel1).coll[Representation, Seq]()
    _ = logger.info(s"Updating ${longPfxed.size} `Representation.lprefx`s to length $URL_PREFIX_LENGTH bytes")
    _ <- Future.sequence { longPfxed.map { repr => // lprefx will have been overwritten upon construction
      c.update(d :~ ID -> repr.id :~ TIMEFROM -> repr.timeFrom, d :~ "$set" -> (d :~ LPREFX -> repr.lprefx))
    }}
  } yield (), 300 seconds)

  /* Ensure that mongo collection has proper `text` index for relevant fields. Note that (apparently) the
   weights must be integers, and if there's any error in how they're specified the index is silently ignored. */
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

  futColl map (_.indexesManager ensure indxs)

  /**
    * Stores provided representation, optionally updating current state if repr ID already exists in database.
    * @return  Returns a `Future` repr ID of either updated or inserted repr.
    */
  def save(repr: Representation, now: Long = DateTime.now.getMillis): Future[String] = for {
    c <- futColl

    // No longer checking if ID and (in case of public repr) link exist in the db, failing on conflict.  Instead
    // let the caller decide when to update an existing repr based on passing in a Representation with an ID that
    // already exists or doesn't.
    opRepr <- retrieveById(repr.id)

    wr <- opRepr match {
      case Some(r) =>
        logger.info(s"Updating existing Representation(id=${r.id}, link=${r.link}, timeFrom=${r.timeFrom}, dt=${r.timeFrom.dt})")
        for {
          wr <- c update(d :~ ID -> repr.id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now)) // retire the old one
          _ <- wr failIfError; // semicolon wouldn't be necessary if used `wr.failIfError` (w/ the dot) instead--weird
          wr <- c insert repr.copy(timeFrom = now, timeThru = INF_TIME) // insert the new one
        } yield wr

      case _ =>
        logger.info(s"Inserting new Representation(id=${repr.id}, link=${repr.link})")
        c insert repr.copy(timeFrom = now)
    }
    _ <- wr failIfError
  } yield repr.id

  /** Retrieves a current (latest) representation by id. */
  def retrieveById(id: String): Future[Option[Representation]] = for {
    c <- futColl
    opRep <- c.find(d :~ ID -> id :~ curnt).one[Representation]
  } yield opRep

  /** Retrieves all representations, including private and previous versions, by Id. */
  def retrieveAllById(id: String): Future[Seq[Representation]] = for {
    c <- futColl
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
    * --ORDER BY score DESC -- actually this is not happening, would require .sort` after `.find`
    */
  def search(ids: Set[String], query: String): Future[Map[String, Representation]] = for {
    c <- futColl
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt :~ "$text" -> (d :~ "$search" -> query)
    pjn = d :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- c.find(sel, pjn).coll[Representation, Seq]()
  } yield seq.map { repr =>
    repr.id -> repr
  }(breakOut[
    Seq[Representation],
    (String, Representation),
    Map[String, Representation]])
}
