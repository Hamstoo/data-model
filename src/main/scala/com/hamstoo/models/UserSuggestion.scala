package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.SharedWith.ShareWithLevel
import com.hamstoo.utils.{ObjectId, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/***
  * Store user suggestion information
  * @param uuid     - user that made share
  * @param username - to whom username share was made
  * @param email    - to whom email share was made
  * @param count    - total count of shares
  * @param id       - document identifier
  */
case class UserSuggestion(uuid: UUID,
                          username: Option[String],
                          email: Option[String],
                          level: ShareWithLevel = SharedWith.Level0.PRIVATE,
                          count: Int = 1,
                          id: ObjectId = generateDbId(Mark.ID_LENGTH))

object UserSuggestion extends BSONHandlers {

  /***
    * Apply method, with injected checks
    */
  def apply(uuid: UUID,
            username: Option[String],
            email: Option[String],
            level: ShareWithLevel = SharedWith.Level0.PRIVATE,
            count: Int = 1,
            id: ObjectId = generateDbId(Mark.ID_LENGTH)): UserSuggestion = {
    require(username.isDefined || email.isDefined, "It should have defined email or username")
    new UserSuggestion(uuid, username, email, level, count, id)
  }

  val US_UUID: String = nameOf[UserSuggestion](_.uuid)
  val US_USERNAME: String = nameOf[UserSuggestion](_.username)
  val US_EMAIL: String = nameOf[UserSuggestion](_.email)
  val US_LEVEL: String = nameOf[UserSuggestion](_.level)
  val US_COUNT: String = nameOf[UserSuggestion](_.count)
  val US_ID: String = nameOf[UserSuggestion](_.id)

  implicit val fmt: BSONDocumentHandler[UserSuggestion] =
    Macros.handler[UserSuggestion]
}

