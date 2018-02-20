package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import com.hamstoo.utils._
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocumentHandler

import scala.concurrent.{ExecutionContext, Future}

/**
  * This class defines base MongoDB related functionality for classes that extend the Annotation trait.
  * @param name name of annotation object like 'highlight' or 'inline note' for logging purpose only
  * @param ec   execution context thread pool
  * @tparam A   this type param must be subtype of Annotation and have a defined BSONDocument handler
  */
abstract class MongoAnnotationDao[A <: Annotation: BSONDocumentHandler]
                                 (name: String,
                                  db: () => Future[DefaultDB])
                                 (implicit marksDao: MongoMarksDao,
                                  userDao: MongoUserDao,
                                  pagesDao: MongoPagesDao,
                                  ec: ExecutionContext)
                extends AnnotationInfo {

  val logger: Logger
  def dbColl(): Future[BSONCollection]
  protected def marksColl(): Future[BSONCollection] = db().map(_ collection "entries")

  // indexes with names for this mongo collection (whichever one it turns out to be)
  protected val indxs: Map[String, Index] =
    Index(MARKID -> Ascending :: TIMETHRU -> Ascending :: Nil) % s"bin-$MARKID-1-$TIMETHRU-1" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap

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
      _ <- wr.failIfError
      _ <- updateUserContentReprInfo(annotation)
    } yield {
      logger.debug(s"$name ${annotation.id} was successfully inserted")
      annotation
    }
  }

  /** Now that user content has changed remove the mark's "user content" repr so that it will be recomputed. */
  private def updateUserContentReprInfo(annotation: A): Future[Unit] = for {
    mbMark <- marksDao.retrieve(User(annotation.usrId), annotation.markId)
    _ <- if (mbMark.isEmpty) Future.unit else for {
      _ <- marksDao.unsetRepr(mbMark.get, Right(ReprType.USER_CONTENT))
      _ <- pagesDao.removeUserContentPage(mbMark.get.id) // this isn't completely necessary, just a little housekeeping
    } yield ()
  } yield ()

  /**
    * Retrieves annotations by mark ID, ignoring whether or not the user is authorized to view them, which
    * means the calling code must perform this check itself.
    * @param markId  Mark ID of requested annotations.
    * @return        Empty sequence if no such annotations are found.
    */
  def retrieveInsecure(markId: ObjectId): Future[Seq[A]] = {
    logger.debug(s"Retrieving (insecure) ${name + "s"} for mark $markId")
    for {
      c <- dbColl()
      seq <- c.find(d :~ MARKID -> markId :~ curnt).coll[A, Seq]()
    } yield {
      logger.debug(s"${seq.size} ${name + "s"} retrieved (insecure)")
      seq
    }
  }

  /**
    * Retrieve annotations from mongodb collection by several parameters
    * @param usr - unique owner identifier
    * @param markId - markId of the web page where annotation was done
    * @return - future with sequence of annotations that match condition
    */
  def retrieve(usr: Option[User], markId: ObjectId): Future[Seq[A]] = {
    logger.debug(s"Retrieving ${name + "s"} for user ${usr.map(_.usernameId)} and mark $markId")
    for {
      insecures <- retrieveInsecure(markId)
      authorizedReads <- Future.sequence(insecures.map(_.isAuthorizedRead(usr)))
    } yield {
      val secures = insecures.view.zip(authorizedReads).filter(_._2).map(_._1).force
      logger.debug(s"${secures.size} ${name + "s"} were retrieved")
      secures
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
      wr <- c.findAndUpdate(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> TIME_NOW))
      annotation <- wr.result[A].map(Future.successful).getOrElse(
        Future.failed(new Exception(s"MongoAnnotationDao.delete: unable to find $name $id")))
      _ <- updateUserContentReprInfo(annotation)
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
            now: Long = TIME_NOW): Future[Unit] = for {
    c <- dbColl()

    // previous impl in RepresentationActor.merge
    //hls <- hlightsDao.retrieve(newMark.userId, newMark.id)
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
