package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models._
import com.hamstoo.utils._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocumentHandler

import scala.concurrent.{ExecutionContext, Future}

/**
  * This class define base mongodb related functionality for classes that extend Annotation trait
  * @param name name of object like: 'Highlight' or 'InlineNote' for logging purpose only
  * @param ex   execution context
  * @tparam A - this type param must be subtype of Annotations and have defined BSONDocument handler
  */
abstract class MongoAnnotationDao[A <: Annotation: BSONDocumentHandler](name: String)(implicit ex: ExecutionContext) extends AnnotationInfo {

  val futColl: Future[BSONCollection]
  val logger: Logger

  /**
    * Insert annotation instance into mongodb collection
    * @param annotation - object that must be inserted
    * @return - Future value with successfully inserted annotations object
    */
  def insert(annotation: A): Future[A] = {
    logger.debug(s"Inserting $name: ${annotation.id}")

    for {
      c <- futColl
      wr <- c insert annotation
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
    logger.debug(s"Retrieving $name by uuid: $usr and id: $id")

    for {
      c <- futColl
      mbHl <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[A]
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
    logger.debug(s"Retrieving ${name + "s"} by uuid: $usr and markId: $markId")

    for {
      c <- futColl
      seq <- (c find d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[A, Seq]()
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
    logger.debug(s"Deleting $name by uuid: $usr and id: $id")

    for {
      c <- futColl
      wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
      _ <- wr failIfError
    } yield logger.debug(s"$name was successfully deleted")
  }

  /**
    * Retrive all annotations from mongodb collection.
    * @return - future with sequence of annotations
    */
  def retrieveAll(): Future[Seq[A]] = {
    logger.debug(s"Retrieving all ${name + "s"}")

    for {
      c <- futColl
      seq <- (c find d).coll[A, Seq]()
    } yield {
      logger.debug(s"${seq.size} ${name + "s"} was successfully retrieved")
      seq
    }
  }
}
