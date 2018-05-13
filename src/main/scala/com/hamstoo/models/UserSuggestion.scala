package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.utils.{ObjectId, generateDbId, _}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/***
  * Store user suggestion information
  * @param uuid     - user that made share
  * @param username - to whom username share was made
  * @param email    - to whom email share was made
  * @param pubVisible  - is this suggestion is publicly visible
  * @param pubEditable - is this suggestion is publicly editable
  * @param id       - document identifier
  */
case class UserSuggestion(uuid: UUID,
                          username: Option[String],
                          email: Option[String],
                          pubVisible: SharedWith.Level.Value,
                          pubEditable: SharedWith.Level.Value,
                          created: TimeStamp,
                          id: ObjectId = generateDbId(Mark.ID_LENGTH)) {
  def identifier: String = username.getOrElse(email.get)
}

object UserSuggestion extends BSONHandlers {

  /***
    * Apply method, with injected checks
    */
  def apply(uuid: UUID,
            username: Option[String],
            email: Option[String],
            pubVisible: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
            pubEditable: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
            created: TimeStamp = TIME_NOW,
            id: ObjectId = generateDbId(Mark.ID_LENGTH)): UserSuggestion = {
    require(username.isDefined || email.isDefined, "It should have defined email or username")
    new UserSuggestion(uuid, username, email, pubVisible, pubEditable, created, id)
  }

  val US_UUID: String = nameOf[UserSuggestion](_.uuid)
  val US_USERNAME: String = nameOf[UserSuggestion](_.username)
  val US_EMAIL: String = nameOf[UserSuggestion](_.email)
  val US_VISIBLE: String = nameOf[UserSuggestion](_.pubVisible)
  val US_EDITABLE: String = nameOf[UserSuggestion](_.pubEditable)
  val US_CREATED: String = nameOf[UserSuggestion](_.created)
  val US_ID: String = nameOf[UserSuggestion](_.id)

  implicit val fmt: BSONDocumentHandler[UserSuggestion] =
    Macros.handler[UserSuggestion]
}

