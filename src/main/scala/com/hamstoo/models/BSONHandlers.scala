package com.hamstoo.models

import java.util.UUID

import org.joda.time.DateTime
import reactivemongo.bson.{BSONBinary, BSONHandler, BSONLong, BSONString, Subtype}

import scala.collection.mutable

/**
  * Trait that houses additional BSON handlers. Should be extended by companion objects of data classes that contain
  * member classes other than the ones serializable by default reactivemongo's handlers.
  */
trait BSONHandlers {
  implicit val arrayBsonHandler: BSONHandler[BSONBinary, mutable.WrappedArray[Byte]] =
    BSONHandler[BSONBinary, mutable.WrappedArray[Byte]](
      _.byteArray,
      a => BSONBinary(a.array, Subtype.GenericBinarySubtype))
  implicit val uuidBsonHandler: BSONHandler[BSONString, UUID] =
    BSONHandler[BSONString, UUID](UUID fromString _.value, BSONString apply _.toString)
  implicit val dateTimeBsonHandler: BSONHandler[BSONLong, DateTime] =
    BSONHandler[BSONLong, DateTime](new DateTime(_), BSONLong apply _.getMillis)
}