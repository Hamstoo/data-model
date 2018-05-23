package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.models.{SharedWith, UserSuggestion}
import com.hamstoo.utils._
import reactivemongo.api.DefaultDB
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONRegex}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/***
  * Provide methods for operation with username-suggestion collection
  */
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB]) extends Dao("user_suggestion") {

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(US_TIMESTAMP -> Ascending :: Nil) % s"bin-$US_TIMESTAMP-1" ::
    Index(US_ID -> Ascending :: US_USERNAME -> Ascending :: US_EMAIL -> Ascending :: Nil, unique = true) %
      s"bin-$US_ID-1-$US_USERNAME-1$US_EMAIL-1-uniq" ::
    Nil toMap

  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 274 seconds)

  /***
    * Save new user suggestion to collection, or update time if already existing
    * @param us - user suggestion that must be inserted
    * @return   - inserted user suggestion
    */
  def save(us: UserSuggestion): Future[UserSuggestion] = {
    val userId = us.userId
    logger.debug(s"Saving user suggestion for user: $userId")

    for {
      c <- dbColl()

      // check for existence
      optExist <- retrieve(userId, us.username, us.email)

      res <- optExist match {

        // if suggestion already exist, update time
        case Some(_) =>
          val now = TIME_NOW
          updateTime(userId, us.id, now).map(_ => us.copy(created = now))

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
    * @param userId - for which user shares
    * @param id   - suggestion id
    * @return     - empty future
    */
  def updateTime(userId: UUID, id: ObjectId, now: TimeStamp): Future[Unit] = {
    logger.debug(s"Increasing count of shares for user: $userId of user suggestion: $id")

    for {
      c <- dbColl()
      sel = d :~ US_USR -> userId :~ US_ID -> id
      upd = d :~ "$set" -> (d :~ US_TIMESTAMP -> now)

      updRes <- c.update(sel, upd)
      _ <- updRes.failIfError
    } yield {
      logger.debug(s"Total count of shares was increased for $id")
    }
  }

  /***
    * Retrieve user suggestion by username/email.
    * Used for checking document existence.
    * @param userId    sharer user identifier
    * @param username  optional username
    * @param email     optional email
    * @return          optional user suggestion
    */
  def exists(userId: UUID, username: Option[String], email: Option[String], isPublic: Option[Boolean]):
                                                                                    Future[Option[UserSuggestion]] = {
    logger.debug(s"Retrieve user suggestion by username: $username and email: $email")

    for {
      c <- dbColl()

      sel = d :~ "$or" -> Seq(
        d :~ username.fold(d)(u => d :~ US_USERNAME -> u) :~ email.fold(d)(e => d :~ US_USERNAME -> e),
        d :~ isPublic.fold(d)(b => if (b) d :~ US_IS_PUBLIC -> b else d))

      mb <- c.find(d :~ US_USR -> userId :~ sel).one[UserSuggestion]

    } yield {
      logger.debug(s"$mb was retrieved")
      mb
    }
  }

  /***
    * Retrieve user suggestion matching by username
    * @param userId  sharer user id
    * @param prefix  search prefix
    * @param offset  offset value, part of pagination functionality
    * @param limit   limit value, part of pagination functionality
    * @return        user suggestions
    */
  def findByUsername(userId: UUID,
                     prefix: String,
                     isPublic: Option[SharedWith.Level.Value] = None,
                     pubEditable: Option[SharedWith.Level.Value] = None,
                     offset: Int = 0,
                     limit: Int = 20): Future[Seq[UserSuggestion]] =
    find(userId, prefix, US_USERNAME, isPublic, offset, limit)

  /***
    * Retrieve user suggestion matching by email
    * @param userId   - for whom
    * @param prefix - search prefix
    * @return       - optional user suggestion
    */
  def findByEmail(userId: UUID,
                  prefix: String,
                  isPublic: Option[SharedWith.Level.Value] = None,
                  offset: Int = 0,
                  limit: Int = 20): Future[Seq[UserSuggestion]] =
    find(userId, prefix, US_EMAIL, isPublic, offset, limit)

  /***
    * Find user suggestion by specified field
    * @param userId        - for whom
    * @param prefix      - search prefix
    * @param fieldName   - by which field we should query. Can be username or email
    * @param isPublic    share view level
    * @return            - user suggestion, if exist.
    */
  private def find(userId: UUID,
                   prefix: String,
                   fieldName: String,
                   isPublic: Option[SharedWith.Level.Value],
                   offset: Int,
                   limit: Int): Future[Seq[UserSuggestion]] = {
    logger.debug(s"Find user suggestion by $fieldName for user: $userId")
    for {
      c <- dbColl()
      sel = regexMatcher(fieldName, prefix) :~ isPublic.fold(d)(e => d :~ US_IS_PUBLIC -> e)
      suggs <- c.find(sel)
        .sort(d :~ US_TIMESTAMP -> -1)
        .pagColl[UserSuggestion, Seq](offset, limit)

    } yield {
      logger.debug(s"$suggs was retrieved")
      suggs
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
      //fieldName -> (d :~ "$exists" -> 1) :~
      // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
      fieldName -> BSONRegex(prefix.toLowerCase + ".*", "i")
  }
}
