package com.hamstoo.daos

import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for profile pictures--implemented with MongoDB's binary keys.
  */
class MongoUserPicDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedWriteResult, d}

  private val futColl: Future[BSONCollection] = db map (_ collection "userpics")
  private val PKEY = "pic"

  /** Saves or updates file bytes by id. */
  def store(id: String, bytes: Array[Byte]): Future[Unit] = for {
    c <- futColl
    upd = d :~ "_id" -> id :~ PKEY -> BSONBinary(bytes, Subtype.GenericBinarySubtype)
    wr <- c update(d :~ "_id" -> id, upd, upsert = true)
    _ <- wr failIfError
  } yield ()

  /** Retrieves file bytes by id. */
  def retrieve(id: String): Future[Option[Array[Byte]]] = for {
    c <- futColl
    optDoc <- (c find d :~ "_id" -> id).one
  } yield for {
    doc <- optDoc
    pic <- doc get PKEY
  } yield pic.asInstanceOf[BSONBinary].byteArray

  // TODO: Check memory footprint and maybe try streaming
}
