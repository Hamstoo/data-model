package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.SharedWith.ShareWithLevel
import com.hamstoo.models.UserSuggestion
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.utils._
import reactivemongo.api.DefaultDB
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONArray, BSONDocument, BSONRegex}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/***
  * Provide methods for operation with username-suggestion collection
  */
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB],
                                  ec: ExecutionContext)
  extends Dao("user-suggestion", classOf[UserSuggestionDao]) {


  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(US_CREATED -> Ascending :: Nil) % s"bin-$US_CREATED-1" :: 
      Index(US_USERNAME -> Ascending :: Nil, unique = true) % s"bin-$US_USERNAME-1-uniq" ::
      Index(US_EMAIL -> Ascending :: Nil, unique = true) % s"bin-$US_EMAIL-1-uniq" :: Nil toMap
  
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 389 seconds)

  /***
    * Insert new user suggestion to collection
    * @param us - user suggestion that must be inserted
    * @return   - inserted user suggestion
    */
  def insert(us: UserSuggestion): Future[UserSuggestion] = {
    logger.debug(s"Inserting new user suggestion for user: ${us.uuid}")
    for {
      c <- dbColl()
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
  def retrieveByUsername(uuid: UUID, prefix: String): Future[Option[UserSuggestion]] =
    retrieve(uuid, prefix, US_USERNAME)

  /***
    * Retrieve user suggestion, by email
    * @param uuid   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def retrieveByEmail(uuid: UUID, prefix: String): Future[Option[UserSuggestion]] =
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
                          limit: Int = 20): Future[Seq[UserSuggestion]] = {
    logger.debug(s"Retrieving suggestion for $uuid by prefix: {$prefix}")

    for {
      c <- dbColl()

      // check by share level
      byLevel = d :~ US_LEVEL -> level

      // check username field
      byUsername = regexMatcher(US_USERNAME, prefix)

      // check email field
      byEmail = regexMatcher(US_EMAIL, prefix)

      sel = d :~ "$and" -> BSONArray(byLevel, d :~ "$or" -> BSONArray(byUsername, byEmail))
      suggs <- c.find(sel)
        .sort(d :~ US_CREATED -> 1)
        .pagColl[UserSuggestion, Seq](offset, limit)
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
  private def retrieve(uuid: UUID, prefix: String, fieldName: String): Future[Option[UserSuggestion]] = {
    logger.debug(s"Retrieving user suggestion by $fieldName for user: $uuid")

    for {
      c <- dbColl()
      optSugg <- c.find(regexMatcher(fieldName, prefix)).one[UserSuggestion]
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
