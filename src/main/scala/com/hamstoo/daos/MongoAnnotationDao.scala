package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark.PAGE
import com.hamstoo.models._
import com.hamstoo.utils._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocumentHandler

import scala.concurrent.{ExecutionContext, Future}

/**
  * This class define base mongodb related functionality for classes that extend Annotation trait
  * @param name name of object like: 'Highlight' or 'InlineNote' for logging purpose only
  * @param ex   execution context
  * @tparam A - this type param must be subtype of Annotations and have defined BSONDocument handler
  */
abstract class MongoAnnotationDao[A <: Annotation: BSONDocumentHandler](name: String, db: () => Future[DefaultDB])
                                                                       (implicit ex: ExecutionContext)
          extends AnnotationInfo {

  val logger: Logger
  def dbColl(): Future[BSONCollection]
  protected def marksColl(): Future[BSONCollection] = db().map(_ collection "entries")

  /**
    * Insert annotation instance into mongodb collection
    * @param annotation - object that must be inserted
    * @return - Future value with successfully inserted annotations object
    */
  def insert(annotation: A): Future[A] = {
    logger.debug(s"Inserting $name ${annotation.id}")

    for {
      c <- dbColl()
      wr <- c.insert(annotation)
      _ <- wr failIfError
    } yield {
      logger.debug(s"$name: ${annotation.id} was successfully inserted")
      annotation
    }
  }

  /**
    * Retrieve annotation object from mongodb collection by several parameters
    * @param usr - unique owner identifier
    * @param id - unique annotation identifier
    * @return - option value with annotation object
    */
  def retrieve(usr: UUID, id: String): Future[Option[A]] = {
    logger.debug(s"Retrieving $name $id for user $usr")

    for {
      c <- dbColl()
      mbHl <- c.find(d :~ USR -> usr :~ ID -> id :~ curnt).projection(d :~ POS -> 1).one[A]
    } yield {
      logger.debug(s"$name: $mbHl was successfully retrieved")
      mbHl
    }
  }

  /**
    * Retrieve annotations from mongodb collection by several parameters
    * @param usr - unique owner identifier
    * @param markId - markId of the web page where annotation was done
    * @return - future with sequence of annotations that match condition
    */
  def retrieveByMarkId(usr: UUID, markId: String): Future[Seq[A]] = {
    logger.debug(s"Retrieving ${name + "s"} for user $usr and mark $markId")

    for {
      c <- dbColl()
      seq <- c.find(d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[A, Seq]()
    } yield {
      logger.debug(s"${seq.size} ${name + "s"} was successfully retrieved")
      seq
    }
  }

  /**
    * Delete annotation object from mongodb collection by several parameters
    * @param usr - unique owner identifier
    * @param id - unique annotation identifier
    * @return - empty future
    */
  def delete(usr: UUID, id: String): Future[Unit] = {
    logger.debug(s"Deleting $name $id for user $usr")

    for {
      c <- dbColl()
      wr <- c.update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
      _ <- wr failIfError
    } yield logger.debug(s"$name was successfully deleted")
  }

  /**
    * Retrive all annotations from mongodb collection.
    * @return - future with sequence of annotations
    */
  @deprecated("Not really deprecated, but sure seems expensive, so warn if it's being used.", "0.9.34")
  def retrieveAll(): Future[Seq[A]] = {
    logger.debug(s"Retrieving all ${name + "s"} (FOR ALL USERS!)")

    for {
      c <- dbColl()
      seq <- c.find(d).coll[A, Seq]()
    } yield {
      logger.debug(s"${seq.size} ${name + "s"} was successfully retrieved")
      seq
    }
  }

  /**
    * Merge two sets of annotations by setting their `timeThru`s to the time of execution and inserting a new
    * mark with the same `timeFrom`.
    */
  def merge(oldMark: Mark, newMark: Mark,
            insrt: (A, String, Long) => Future[A],
            now: Long = DateTime.now.getMillis): Future[Unit] = for {
    c <- dbColl()

    // previous impl in RepresentationActor.merge
    //hls <- hlightsDao.retrieveByMarkId(newMark.userId, newMark.id)
    //_ <- Future.sequence { hls.map(x => hlIntersectionSvc.add(x.copy(markId = oldMark.id))) }

    newAnnotations <- c.find(d :~ USR -> newMark.userId :~ MARKID -> newMark.id :~ curnt).coll[A, Seq]()

    // set each of the new mark's annotations' timeThrus to the current time (which is required because of the
    // bin-usrId-1-id-1-timeThru-1-uniq unique indexes on these collections) and then re-insert them into
    // the DB with the old mark's ID
    _ <- Future.sequence { newAnnotations.map { a: A =>
      for {
        wr <- c.update(d :~ USR -> a.usrId :~ ID -> a.id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now))
        _ = wr.failIfError
        merged <- insrt(a, oldMark.id, now)
      } yield merged
    }}

  } yield ()
}
