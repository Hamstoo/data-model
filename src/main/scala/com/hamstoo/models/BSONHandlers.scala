package com.hamstoo.models

import java.util.UUID

import akka.util.ByteString
import com.hamstoo.models.SharedWith.ShareWithLevel
import org.joda.time.DateTime
import reactivemongo.bson.{BSONBinary, BSONHandler, BSONInteger, BSONLong, BSONString, Subtype}

import scala.collection.mutable

/**
  * Trait that houses additional BSON handlers. Should be extended by companion objects of data classes that contain
  * member classes other than the ones serializable by default reactivemongo's handlers.
  */
trait BSONHandlers {
  implicit val arrayBsonHandler: BSONHandler[BSONBinary, mutable.WrappedArray[Byte]] =
    BSONHandler(_.byteArray, a => BSONBinary(a.array, Subtype.GenericBinarySubtype))
  implicit val byteStringBsonHandler: BSONHandler[BSONBinary, ByteString] =
    BSONHandler(ByteString apply _.byteArray, b => BSONBinary(b.toArray, Subtype.GenericBinarySubtype))
  implicit val uuidBsonHandler: BSONHandler[BSONString, UUID] =
    BSONHandler[BSONString, UUID](UUID fromString _.value, BSONString apply _.toString)
  implicit val dateTimeBsonHandler: BSONHandler[BSONLong, DateTime] =
    BSONHandler[BSONLong, DateTime](b => new DateTime(b.value), BSONLong apply _.getMillis)
  implicit val sharedWithLevelValueHandler: BSONHandler[BSONInteger, ShareWithLevel] =
    BSONHandler[BSONInteger, ShareWithLevel](b => SharedWith.Level0.withValue(b.value), BSONInteger apply _.value)
}
