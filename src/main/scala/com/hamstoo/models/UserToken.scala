package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * A token for confirming a new user's email address or for resetting an existing user's password.
  * @param id              Token ID.
  * @param userId          User ID.
  * @param email           User's email address.
  * @param expirationTime  Token expiration datetime (defaults to 4 days in the future).
  * @param isSignUp        True if this token is a signup token; false if the token is to reset a password.
  */
case class UserToken(
                      id: UUID = UUID.randomUUID,
                      userId: UUID,
                      email: String,
                      expirationTime: DateTime = UserToken.DEFAULT_EXPIRY,
                      isSignUp: Boolean) {

  def isExpired: Boolean = expirationTime.isBeforeNow
}

object UserToken extends BSONHandlers {

  def DEFAULT_EXPIRY: DateTime = new DateTime().plusDays(4)

  val ID: String = com.hamstoo.models.Mark.ID;  assert(nameOf[UserToken](_.id) == ID)
  val USR: String = com.hamstoo.models.Mark.USR;  assert(nameOf[UserToken](_.userId) == USR)
  val ISSIGNUP: String = nameOf[UserToken](_.isSignUp)
  val EXPIRY: String = nameOf[UserToken](_.expirationTime)
  implicit val tokenHandler: BSONDocumentHandler[UserToken] = Macros.handler[UserToken]
}
