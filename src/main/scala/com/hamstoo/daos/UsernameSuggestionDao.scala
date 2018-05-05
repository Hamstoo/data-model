package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.SharedWith.ShareWithLevel
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

  /***
    * Insert new user suggestion to collection
    * @param us - user suggestion that must be inserted
    * @return   - inserted user suggestion
    */
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
    * Retrieve user suggestion, by username
    * @param uuid   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def retrieveByUsername(uuid: UUID, prefix: String): Future[Option[UsernameSuggestion]] =
    retrieve(uuid, prefix, US_USERNAME)

  /***
    * Retrieve user suggestion, by email
    * @param uuid   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def retrieveByEmail(uuid: UUID, prefix: String): Future[Option[UsernameSuggestion]] =
    retrieve(uuid, prefix, US_EMAIL)


  /***
    * Retrieve user suggestions for user
    * @param uuid   - user identifier
    * @param prefix - searable prefix
    * @param level  - share level
    * @param offset - offset value, part of pagination functionality
    * @param limit  - limit value, part of pagination functionality
    * @return       - user suggestions
    */
  def retrieveSuggestions(uuid: UUID,
                          prefix: String,
                          level: ShareWithLevel,
                          offset: Int = 0,
                          limit: Int = 20): Future[Seq[UsernameSuggestion]] = {
    logger.debug(s"Retrieving suggestion for $uuid by prefix: {$prefix}")

    for {
      c <- coll()

      // check by share level
      byLevel = d :~ US_LEVEL -> level

      // check username field
      byUsername = regexMatcher(US_USERNAME, prefix)

      // check email field
      byEmail = regexMatcher(US_EMAIL, prefix)

      sel = d :~ "$and" -> BSONArray(byLevel, d :~ "$or" -> BSONArray(byUsername, byEmail))
      suggs <- c.find(sel).pagColl[UsernameSuggestion, Seq](offset, limit)
    } yield {
      logger.debug(s"${suggs.size} user suggestion was retrieved for user: $uuid")
      suggs
    }
  }

  /***
    * Retrieve user suggestion by specified field
    * @param uuid      - for whom
    * @param prefix    - search prefix
    * @param fieldName - by which field we should query. Can be username or email
    * @return          - user suggestion, if exist.
    */
  // should be add level parameter here?
  private def retrieve(uuid: UUID, prefix: String, fieldName: String): Future[Option[UsernameSuggestion]] = {
    logger.debug(s"Retrieving user suggestion by $fieldName for user: $uuid")

    for {
      c <- coll()
      optSugg <- c.find(regexMatcher(fieldName, prefix)).one[UsernameSuggestion]
    } yield {
      logger.debug(s"$optSugg was retrieved")
      optSugg
    }
  }

  /***
    * Create BSON document selector for specified fieldName and prefix
    * @param fieldName - field name
    * @param prefix    - search prefix
    * @return          - BSON document selector
    */
  private def regexMatcher(fieldName: String, prefix: String): BSONDocument = {
    d :~
      // check if username exists to skip empty usernames if data migration wasn't successfull,
      fieldName -> (d :~ "$exists" -> 1) :~
      // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
      fieldName -> BSONRegex(".*" + prefix.toLowerCase + ".*", "i")
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
                                level: ShareWithLevel = SharedWith.Level0.PRIVATE,
                                created: TimeStamp = TIME_NOW)

  val US_UUID: String = nameOf[UsernameSuggestion](_.uuid)
  val US_USERNAME: String = nameOf[UsernameSuggestion](_.username)
  val US_EMAIL: String = nameOf[UsernameSuggestion](_.email)
  val US_LEVEL: String = nameOf[UsernameSuggestion](_.level)
  val US_CREATED: String = nameOf[UsernameSuggestion](_.created)

  implicit val fmt: BSONDocumentHandler[UsernameSuggestion] =
    Macros.handler[UsernameSuggestion]
}
