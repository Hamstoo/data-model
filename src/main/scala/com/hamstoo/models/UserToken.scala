package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.fieldName
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, BSONHandler, BSONLong, Macros}

case class UserToken(
                      id: UUID = UUID.randomUUID,
                      userId: UUID,
                      email: String,
                      expirationTime: DateTime = new DateTime() plusHours 12,
                      isSignUp: Boolean) {
  def isExpired: Boolean = expirationTime.isBeforeNow
}

object UserToken {
  val ID: String = fieldName[UserToken]("id")
  private implicit val uuidHandler = com.hamstoo.models.User.uuidBsonHandler
  implicit val dateTimeHandler: BSONHandler[BSONLong, DateTime] =
    BSONHandler[BSONLong, DateTime](new DateTime(_), BSONLong apply _.getMillis)
  implicit val tokenHandler: BSONDocumentHandler[UserToken] = Macros.handler[UserToken]
}
