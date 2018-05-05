package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.SharedWith.ShareWithLevel
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/***
  * Store user suggestion information
  * @param uuid     - user that made share
  * @param username - to whom username share was made
  * @param email    - to whom email share was made
  * @param level    - share level
  */
case class UserSuggestion(uuid: UUID,
                          username: Option[String],
                          email: Option[String],
                          level: ShareWithLevel = SharedWith.Level0.PRIVATE,
                          created: TimeStamp = TIME_NOW)

object UserSuggestion extends BSONHandlers {

  val US_UUID: String = nameOf[UserSuggestion](_.uuid)
  val US_USERNAME: String = nameOf[UserSuggestion](_.username)
  val US_EMAIL: String = nameOf[UserSuggestion](_.email)
  val US_LEVEL: String = nameOf[UserSuggestion](_.level)
  val US_CREATED: String = nameOf[UserSuggestion](_.created)

  implicit val fmt: BSONDocumentHandler[UserSuggestion] =
    Macros.handler[UserSuggestion]
}

