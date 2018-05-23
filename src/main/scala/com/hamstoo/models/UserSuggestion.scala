package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.utils.{ObjectId, generateDbId, _}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/***
  * User suggestions for during search and when determining recent sharees during sharing.
  * @param userId  user that made share
  * @param sharee  to whom username share was made (email address or username) or None for completely public shares
  * @param shareeUsername  if sharee is the email address of a user, then this field holds that user's username
  * @param ts        timestamp, which can be updated (which is why we don't call this field `created`)
  * @param id        document identifier
  */
case class UserSuggestion(userId: UUID,
                          sharee: Option[String],
                          shareeUsername: Option[String],
                          ts: TimeStamp,
                          id: ObjectId = generateDbId(Mark.ID_LENGTH)) {
}

object UserSuggestion extends BSONHandlers {

  val US_USR: String = nameOf[UserSuggestion](_.userId)
  val US_SHAREE: String = nameOf[UserSuggestion](_.sharee)
  val US_TIMESTAMP: String = nameOf[UserSuggestion](_.ts)
  val US_ID: String = nameOf[UserSuggestion](_.id)

  implicit val fmt: BSONDocumentHandler[UserSuggestion] = Macros.handler[UserSuggestion]
}

