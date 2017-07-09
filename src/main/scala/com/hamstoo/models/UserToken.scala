package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

case class UserToken(
                      id: UUID = UUID.randomUUID,
                      userId: UUID,
                      email: String,
                      expirationTime: DateTime = new DateTime() plusHours 12,
                      isSignUp: Boolean) {
  def isExpired: Boolean = expirationTime.isBeforeNow
}

object UserToken extends BSONHandlers {
  val ID: String = nameOf[UserToken](_.id)
  implicit val tokenHandler: BSONDocumentHandler[UserToken] = Macros.handler[UserToken]
}
