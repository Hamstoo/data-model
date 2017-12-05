package com.hamstoo.daos

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.ExpectedRating
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for MongoDB `eratings` collection.
  * @param db  Future[DefaultDB] database connection returning function.
  */
class MongoExpectedRatingDao(db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._

  // field names
  import com.hamstoo.models.Mark.{ID, /*TIMEFROM,*/ TIMETHRU}
  assert(nameOf[ExpectedRating](_.id) == ID)
  //assert(nameOf[ExpectedRating](_.timeFrom) == TIMEFROM)
  assert(nameOf[ExpectedRating](_.timeThru) == TIMETHRU)
  //val VALUE: String = nameOf[ExpectedRating](_.value)
  //val N: String = nameOf[ExpectedRating](_.n)

  val logger: Logger = Logger(classOf[MongoExpectedRatingDao])

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "eratings")

  // ensure indexes
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 394 seconds)

  /** Stores provided expected rating, optionally updating current state if er ID already exists in database. */
  def save(er: ExpectedRating, now: Long = DateTime.now.getMillis): Future[Unit] = for {
    c <- dbColl()
    optER <- retrieve(er.id)
    wr <- optER match {
      case Some(r) =>
        logger.info(s"Updating existing $r")
        for {
          wr <- c.update(d :~ ID -> er.id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now)) // retire the old one
          _ <- wr.failIfError
          wr <- c.insert(er.copy(timeFrom = now, timeThru = INF_TIME)) // insert the new one
        } yield wr

      case _ =>
        logger.info(s"Inserting new $er")
        c.insert(er.copy(timeFrom = now))
    }
    _ <- wr failIfError
  } yield ()

  /** Retrieves a current (latest) expected rating by ID. */
  def retrieve(id: String): Future[Option[ExpectedRating]] = retrieve(Set(id)).map(_.get(id))

  /** Given a set of ExpectedRating IDs, return a mapping from ID to instance. */
  def retrieve(ids: Set[String]): Future[Map[String, ExpectedRating]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving with expected ratings (first 5): ${ids.take(5)}")
    seq <- c.find(d :~ ID -> (d :~ "$in" -> ids) :~ curnt).coll[ExpectedRating, Seq]()

  } yield seq.map { erating =>
    erating.id -> erating

  }.toMap/*(breakOut[
    Seq[ExpectedRating],
    (String, ExpectedRating),
    Map[String, ExpectedRating]])*/
}
