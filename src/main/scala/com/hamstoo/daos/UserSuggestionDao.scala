package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.models.{SharedWith, UserSuggestion}
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
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB], ec: ExecutionContext)
  extends Dao("user-suggestion", classOf[UserSuggestionDao]) {


  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(US_CREATED -> Ascending :: Nil) % s"bin-$US_CREATED-1" ::
      Index(US_ID -> Ascending ::
            US_USERNAME -> Ascending ::
            US_EMAIL -> Ascending :: Nil, unique = true) % s"bin-$US_ID-1-$US_USERNAME-1$US_EMAIL-1-uniq"::
      Nil toMap

  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 389 seconds)

  /***
    * Save new user suggestion to collection, or update time if already existing
    * @param us - user suggestion that must be inserted
    * @return   - inserted user suggestion
    */
  def save(us: UserSuggestion): Future[UserSuggestion] = {
    val uuid = us.uuid
    logger.debug(s"Saving user suggestion for user: $uuid")

    for {
      c <- dbColl()

      // check for existence
      optExist <- retrieve(uuid, us.username, us.email)

      res <- optExist match {

        // if suggestion already exist, update time
        case Some(_) =>
          val now = TIME_NOW
          updateTime(uuid, us.id, now).map(_ => us.copy(created = now))

        // otherwise, insert into collection
        case _ =>
          c.insert(us).flatMap(_.failIfError).map(_ => us)
      }
    } yield {
      logger.debug("User suggestion was saved")
      res
    }
  }

  /***
    * Update time of creation
    * @param uuid - for which user shares
    * @param id   - suggestion id
    * @return     - empty future
    */
  def updateTime(uuid: UUID, id: ObjectId, now: TimeStamp): Future[Unit] = {
    logger.debug(s"Increasing count of shares for user: $uuid of user suggestion: $id")

    for {
      c <- dbColl()
      sel = d :~ US_UUID -> uuid :~ US_ID -> id
      upd = d :~ "$set" -> (d :~ US_CREATED -> now)

      updRes <- c.update(sel, upd)
      _ <- updRes.failIfError
    } yield {
      logger.debug(s"Total count of shares was increased for $id")
    }
  }

  /***
    * Retrieve user suggestion by username/email.
    * Used for checking document existence.
    * @param uuid     - user identifier
    * @param username - optional username
    * @param email    - optional email
    * @return         - optional user suggestion
    */
  def retrieve(uuid: UUID, username: Option[String], email: Option[String]): Future[Option[UserSuggestion]] = {
    logger.debug(s"Retrieve user suggestion by username: $username and email: $email")

    for {
      c <- dbColl()

      // unchecked, because we validate (None, None) case, by UserSuggestion.apply method
      sel = (username -> email: @unchecked) match {
        case (None, Some(e)) => d :~ US_EMAIL -> e
        case (Some(u), None) => d :~ US_USERNAME -> u
        case (Some(u), Some(e)) => d :~ US_USERNAME -> u :~ US_EMAIL -> e
      }

      optSugg <- c.find(d :~ US_UUID -> uuid :~ sel).one[UserSuggestion]
    } yield {
      logger.debug(s"$optSugg was retrieved")
      optSugg
    }
  }

  /***
    * Retrieve user suggestion matching by username
    * @param uuid   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def findByUsername(
                      uuid: UUID,
                      prefix: String,
                      pubVisible: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
                      pubEditable: SharedWith.Level.Value = SharedWith.Level.PRIVATE): Future[Option[UserSuggestion]] =
    find(uuid, prefix, US_USERNAME, pubVisible, pubEditable)

  /***
    * Retrieve user suggestion matching by email
    * @param uuid   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def findByEmail(
                   uuid: UUID,
                   prefix: String,
                   pubVisible: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
                   pubEditable: SharedWith.Level.Value = SharedWith.Level.PRIVATE): Future[Option[UserSuggestion]] =
    find(uuid, prefix, US_EMAIL, pubVisible, pubEditable)


  /***
    * Find user suggestions for user
    * @param uuid        - user identifier
    * @param prefix      - searable prefix
    * @param pubVisible  - publicly visible
    * @param pubEditable - publicly editable
    * @param offset      - offset value, part of pagination functionality
    * @param limit       - limit value, part of pagination functionality
    * @return            - user suggestions
    */
  def findSuggestions(
                       uuid: UUID,
                       prefix: String,
                       pubVisible: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
                       pubEditable: SharedWith.Level.Value = SharedWith.Level.PRIVATE,
                       offset: Int = 0,
                       limit: Int = 20): Future[Seq[UserSuggestion]] = {
    logger.debug(s"Retrieving suggestion for $uuid by prefix: {$prefix}")

    for {
      c <- dbColl()

      // check by visibility
      pubVis = d :~ US_VISIBLE -> pubVisible

      // check by editability
      pubEdi = d :~ US_EDITABLE -> pubEditable

      // check username field
      usename = regexMatcher(US_USERNAME, prefix)

      // check email field
      email = regexMatcher(US_EMAIL, prefix)

      sel = d :~ "$and" -> BSONArray(pubVis :~ pubEdi, d :~ "$or" -> BSONArray(usename, email))
      suggs <- c.find(sel)
        .sort(d :~ US_CREATED -> -1)
        .pagColl[UserSuggestion, Seq](offset, limit)
    } yield {
      logger.debug(s"${suggs.size} user suggestion was retrieved for user: $uuid")
      suggs
    }
  }

  /***
    * Find user suggestion by specified field
    * @param uuid        - for whom
    * @param prefix      - search prefix
    * @param fieldName   - by which field we should query. Can be username or email
    * @param pubVisible  - share view level
    * @param pubEditable - share edit level
    * @return            - user suggestion, if exist.
    */
  private def find(
                    uuid: UUID,
                    prefix: String,
                    fieldName: String,
                    pubVisible: SharedWith.Level.Value,
                    pubEditable: SharedWith.Level.Value): Future[Option[UserSuggestion]] = {
    logger.debug(s"Find user suggestion by $fieldName for user: $uuid")

    for {
      c <- dbColl()
      rights = d :~ US_VISIBLE -> pubVisible :~ US_EDITABLE -> pubEditable
      optSugg <- c.find(rights :~ regexMatcher(fieldName, prefix)).one[UserSuggestion]
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
