package com.hamstoo.daos

import com.hamstoo.models.ReprEngineProduct
import com.hamstoo.utils._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocumentHandler

import scala.concurrent.{ExecutionContext, Future}

/**
  * This class defines base MongoDB related functionality for classes that extend the ReprEngineProduct trait.
  * @param name name of object like 'representation' or 'expected rating' for logging purpose only
  * @param ec   execution context
  * @tparam T   this type param must be subtype of Annotations and have defined BSONDocument handler
  */
abstract class MongoReprEngineProductDao[T <: ReprEngineProduct[T]: BSONDocumentHandler]
                                        (name: String, db: () => Future[DefaultDB])
                                        (implicit ec: ExecutionContext) {

  import com.hamstoo.models.Mark.{ID, TIMETHRU}

  val logger: Logger

  def dbColl(): Future[BSONCollection]

  /**
    * Inserting representation to database
    * @param repr  representation
    * @return      inserted representation
    */
  def insert(repr: T): Future[T] = {
    logger.debug(s"Inserting new $name with ID '${repr.id}' [${repr.timeFrom}/${repr.timeFrom.dt}]")
    for {
      c <- dbColl()
      wr <- c.insert(repr)
      _ <- wr.failIfError
    } yield {
      logger.debug(s"Successfully inserted $name with ID '${repr.id}' [${repr.timeFrom}/${repr.timeFrom.dt}]")
      repr
    }
  }

  /**
    * Update representation
    * @param repr  new representation
    * @param now   time
    * @return      updated representation
    */
  def update(repr: T, now: Long = TIME_NOW): Future[T] = {
    logger.info(s"Updating existing $name with ID '${repr.id}' [${repr.timeFrom}/${repr.timeFrom.dt}]")
    for {
      c <- dbColl()
      wr <- c.update(d :~ ID -> repr.id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now)) // retire the old one
      _ <- wr failIfError; // semicolon wouldn't be necessary if used `wr.failIfError` (w/ the dot) instead--weird
      updatedRepr <- insert(repr.withTimeFrom(now))
    } yield {
      logger.debug(s"Successfully updated $name with ID '${repr.id}' [${repr.timeFrom}/${repr.timeFrom.dt}]")
      updatedRepr
    }
  }

  /**
    * Stores provided representation, optionally updating current state if repr ID already exists in database.
    * @return  a `Future` repr ID of either updated or inserted repr
    */
  def save(repr: T, now: Long = TIME_NOW): Future[String] = {
    retrieve(repr.id).flatMap {
      case Some(_) => update(repr, now = now)
      case _       => insert(repr.withTimeFrom(now))
    } map(_.id)
  }

  /** Retrieves a current (latest) representation by ID. */
  def retrieve(id: String): Future[Option[T]] = retrieve(Set(id)).map(_.get(id))

  /** Given a set of representation IDs, return a mapping from ID to instance. */
  def retrieve(ids: Set[String]): Future[Map[String, T]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving ${name}s (first 5): ${ids.take(5)}")
    seq <- c.find(d :~ ID -> (d :~ "$in" -> ids) :~ curnt).coll[T, Seq]()

  } yield seq.map { repr => repr.id -> repr }.toMap/*(breakOut[Seq[ExpectedRating],
                                                               (String, ExpectedRating),
                                                               Map[String, ExpectedRating]])*/

  /** Retrieves all representations, including previous versions, by ID. */
  def retrieveAll(id: String): Future[Seq[T]] = for {
    c <- dbColl()
    seq <- c.find(d :~ ID -> id).coll[T, Seq]()
  } yield seq
}
