package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models._
import com.hamstoo.utils._
import org.joda.time.DateTime
import reactivemongo.api.BSONSerializationPack.{Reader, Writer}
import reactivemongo.api.collections.bson.BSONCollection

import scala.concurrent.{ExecutionContext, Future}

abstract class MongoContentDao[A <: Content](implicit ex: ExecutionContext) extends BSONHandlers with ContentInfo with Logging {

  val futColl: Future[BSONCollection]

  def insert(content: A)(implicit writer: Writer[A]): Future[A] = for {
    c <- futColl
    wr <- c insert content
    _ <- wr failIfError
  } yield content

  def retrieve(usr: UUID, id: String)(implicit reader: Reader[A]): Future[Option[A]] = for {
    c <- futColl
    mbHl <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[A]
  } yield mbHl

  /** Requires `usr` argument so that index can be used for lookup. */
  def retrieveByMarkId(usr: UUID, markId: String)(implicit reader: Reader[A]): Future[Seq[A]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[A, Seq]()
  } yield seq

  def delete(usr: UUID, id: String): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()

  def retrieveAll()(implicit reader: Reader[A]): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d).coll[InlineNote, Seq]()
  } yield seq
}
