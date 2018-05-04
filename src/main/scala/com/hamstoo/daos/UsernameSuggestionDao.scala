package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{BSONHandlers, SharedWith}
import com.hamstoo.utils._
import reactivemongo.api.DefaultDB
import reactivemongo.bson.{BSONArray, BSONDocument, BSONDocumentHandler, BSONRegex, Macros}

import scala.concurrent.{ExecutionContext, Future}

/***
  * Provide methods for operation with username-suggestion collection
  */
class UsernameSuggestionDao(val db: () => Future[DefaultDB])(implicit ex: ExecutionContext)
  extends Dao("user-suggestion", classOf[UsernameSuggestionDao]) {

  import UsernameSuggestionDao._

  /** Insert recently shared username info */
  def insert(us: UsernameSuggestion): Future[UsernameSuggestion] = {
    logger.debug(s"Inserting new user suggestion for user: ${us.uuid}")
    for {
      c <- coll()
      insRes <- c.insert(us)
      _ <- insRes.failIfError
    } yield {
      logger.debug("New user suggestion was inserted")
      us
    }
  }

  /***
    * Retrieve user suggestions for user
    * @param uuid   - user identifier
    * @param prefix - searable prefix
    * @param level  - share level
    * @return       - user suggestions
    */
  def retrieveSuggestions(uuid: UUID, prefix: String, level: SharedLevel): Future[Seq[UsernameSuggestion]] = {
    logger.debug(s"Retrieving suggestion for $uuid by prefix: {$prefix}")

    /** create BSON document for searching field values bt regex */
    def checkByField(fieldName: String, prefix: String): BSONDocument = {
      d :~
        // check if username exists to skip empty usernames if data migration wasn't successfull,
        fieldName -> (d :~ "$exists" -> 1) :~
        // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
        fieldName -> BSONRegex(".*" + prefix.toLowerCase + ".*", "i")
    }

    for {
      c <- coll()

      // check username field
      byUsername = checkByField(US_USERNAME, prefix)

      // check email field
      byEmail = checkByField(US_EMAIL, prefix)

      sel = d :~ "$or" -> BSONArray(byUsername, byEmail)
      sugg <- c.find(sel).coll[UsernameSuggestion, Seq]()
    } yield {
      logger.debug(s"${sugg.size} user suggestion was retrieved for user: $uuid")
      sugg
    }
  }
}

object UsernameSuggestionDao extends BSONHandlers {

  import com.github.dwickern.macros.NameOf._

  type SharedLevel = SharedWith.Level.Value

  /***
    * Store user suggestion information
    * @param uuid     - user that made share
    * @param username - to whom username share was made
    * @param email    - to whom email share was made
    * @param level    - share level
    */
  case class UsernameSuggestion(uuid: UUID,
                                username: Option[String],
                                email: Option[String],
                                level: SharedWith.Level.Value = SharedWith.Level.PRIVATE)

  val US_UUID: String = nameOf[UsernameSuggestion](_.uuid)
  val US_USERNAME: String = nameOf[UsernameSuggestion](_.username)
  val US_EMAIL: String = nameOf[UsernameSuggestion](_.email)
  val US_LEVEL: String = nameOf[UsernameSuggestion](_.level)

  implicit val fmt: BSONDocumentHandler[UsernameSuggestion] =
    Macros.handler[UsernameSuggestion]
}
