package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models._
import com.hamstoo.utils._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.BSONSerializationPack.{Reader, Writer}
import reactivemongo.api.collections.bson.BSONCollection

import scala.concurrent.{ExecutionContext, Future}

/**
  *
  * @param name name of object like: 'Highlight' or 'InlineNote' for logging purpose only
  * @param ex   execution context
  * @tparam A
  */
abstract class MongoContentDao[A <: Content](name: String)(implicit ex: ExecutionContext) extends BSONHandlers with ContentInfo {

  val futColl: Future[BSONCollection]
  val log: Logger

  /** Inserting content */
  def insert(content: A)(implicit writer: Writer[A]): Future[A] = {
    log.debug(s"Inserting $name: ${content.id}")

    for {
      c <- futColl
      wr <- c insert content
      _ <- wr failIfError
    } yield {
      log.debug(s"$name: ${content.id} was successfully inserted")
      content
    }
  }

  def retrieve(usr: UUID, id: String)(implicit reader: Reader[A]): Future[Option[A]] = {
    log.debug(s"Retrieving $name by uuid: $usr and id: $id")

    for {
      c <- futColl
      mbHl <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[A]
    } yield {
      log.debug(s"$name: $mbHl was successfully retrieved")
      mbHl
    }
  }

  /** Requires `usr` argument so that index can be used for lookup. */
  def retrieveByMarkId(usr: UUID, markId: String)(implicit reader: Reader[A]): Future[Seq[A]] = {
    log.debug(s"Retrieving ${name + "s"} by uuid: $usr and markId: $markId")

    for {
      c <- futColl
      seq <- (c find d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[A, Seq]()
    } yield {
      log.debug(s"${seq.size} ${name + "s"} was successfully retrieved")
      seq
    }
  }

  def delete(usr: UUID, id: String): Future[Unit] = {
    log.debug(s"Deleting $name by uuid: $usr and id: $id")

    for {
      c <- futColl
      wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
      _ <- wr failIfError
    } yield log.debug(s"$name was successfully deleted")
  }


  def retrieveAll()(implicit reader: Reader[A]): Future[Seq[A]] = {
    log.debug(s"Retrieving all ${name + "s"}")

    for {
      c <- futColl
      seq <- (c find d).coll[A, Seq]()
    } yield {
      log.debug(s"${seq.size} ${name + "s"} was successfully retrieved")
      seq
    }
  }
}
