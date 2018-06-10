package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserSuggestion
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.utils._
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.api.{Cursor, DefaultDB}
import reactivemongo.bson.BSONRegex

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Provide methods for operation with username-suggestion collection
  */
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB],
                                  userDao: UserDao)
    extends Dao("usersuggestions") {

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(OWNER_ID -> Ascending :: SHAREE -> Ascending :: Nil, unique = true) %
      s"bin-$OWNER_ID-1-$SHAREE-1-uniq" ::
//      Index(OWNER_UNAME -> Ascending :: Nil) % s"bin-$OWNER_UNAME-1" ::
      Index(SHAREE -> Ascending :: Nil) % s"bin-$SHAREE-1" ::
      Index(SHAREE_UNAME -> Ascending :: Nil) % s"bin-$SHAREE_UNAME-1" ::
      Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 274 seconds)

  /**
    * Save new user suggestion to collection and/or update `shareeUsername` and `ts` of existing.
    * @param userId  owner user identifier
    * @param sharee  optional username or email address of sharee (share recipient)
    * @return        upserted user suggestion
    */
  def save(userId: UUID, sharee: Option[String]): Future[UserSuggestion] =
    for {
      c <- dbColl()
      us <- UserSuggestion(userId, sharee) // construct UserSuggestion with updated `shareeUsername` and `ts`
      _ = logger.debug(s"Saving user suggestion: $us")

      sel = d :~ OWNER_ID -> userId :~
        sharee.fold(d :~ SHAREE -> (d :~ "$exists" -> 0))(u => d :~ SHAREE -> u)

      wr <- c.update(sel, us, upsert = true)
      _ <- wr.failIfError
      if wr.n == 1 // don't use nModified here b/c we're upserting, not updating

    } yield {
      logger.debug("User suggestion saved")
      us
    }

  /** Removes an existing user suggestion.  Probably should only be used when sharee==None. */
  def delete(ownerUserId: UUID, sharee: Option[String] = None): Future[Unit] =
    for {
      c <- dbColl()
      _ = logger.debug(s"Removing user suggestion: $ownerUserId / $sharee")
      sel = d :~ OWNER_ID -> ownerUserId :~
        sharee.fold(d :~ SHAREE -> (d :~ "$exists" -> false))(u =>
          d :~ SHAREE -> BSONRegex(s"^$u$$", "i"))
      wr <- c.remove(sel)
      _ <- wr.failIfError
    } yield {
      logger.debug(s"Removed ${wr.n} user suggestions")
      ()
    }

//  /**
//    * Retrieve search suggestion.
//    * @param ownerUserId  current user (used as sharee in this function)
//    * @return             sequence of mark owner usernames starting with prefix
//    */
//  def searchSuggestions(ownerUserId: UUID,
//                        ownerUsernamePrefix: String): Future[Seq[String]] =
//    for {
//      c <- dbColl()
//      _ = logger.debug(s"searchSuggestions($ownerUserId, $ownerUsernamePrefix)")
//
//      mbShareeUsername <- userDao
//        .retrieveById(ownerUserId)
//        .map(_.flatMap(_.userData.usernameLower))
//
//      // be sure not to use SHAREE here in order to user proper index for search
//      isPublic = d :~ SHAREE_UNAME -> (d :~ "$exists" -> false)
//
//      // either shared-to the current user directly or completely public
//      shareeSel = mbShareeUsername.fold(isPublic) { unl =>
//        d :~ "$or" -> Seq(d :~ SHAREE_UNAME -> BSONRegex("^" + unl + "$", "i"),
//                          isPublic)
//      }
//
//      ownerSel = if (ownerUsernamePrefix.isEmpty) d
//      else
//        d :~ OWNER_UNAME -> BSONRegex(
//          ".*" + ownerUsernamePrefix.toLowerCase + ".*",
//          "i")
//
//      seq <- c.find(shareeSel :~ ownerSel).coll[UserSuggestion, Seq]()
//
//    } yield {
//      logger.debug(s"searchSuggestions retrieved ${seq.size} documents")
//      val now = TIME_NOW
//
//      // put those shared directly from first, then public (note the negative symbol)
//      seq
//        .map(us => us.ownerUsername -> (us.sharee.fold(0L)(_ => now) + us.ts))
//        .groupBy(_._1)
//        .toSeq
//        .sortBy(_._2.map(_._2).max)
//        .map(_._1)
//        .take(30)
//    }

  /**
    * Retrieve all users that make marks visible for specified user
    * @param userId - user identifier
    * @param limit  - limit, for result pagination
    * @return       - list of usernames that makes share for specified userId
    */
  def retrieveWhoShared(userId: UUID, limit: Int = 20): Future[Seq[String]] = {
    for {
      c <- dbColl()
      _ = logger.debug(s"Searching user who make share with user: $userId...")

      optUser <- userDao.retrieveById(userId).map(_.map(_.identifier))

      _ = logger.debug(s"Found identifier $optUser.")

      sugg <- optUser.fold(Future.successful(Seq.empty[UserSuggestion])) { id =>
        c.find(d :~ "$or" -> Seq(d :~ SHAREE_UNAME -> id, d :~ SHAREE -> id))
          .sort(d :~ TIMESTAMP -> -1)
          .cursor[UserSuggestion]()
          .collect[Seq](limit, Cursor.FailOnError[Seq[UserSuggestion]]())
      }

      identifiers <- Future
        .sequence(sugg.map(s => userDao.retrieveById(s.ownerUserId)))
        .map(_.flatten)
        .map(_.map(_.usernameId))

    } yield {
      logger.debug(s"${identifiers.size} were retrieved.")
      identifiers
    }
  }

  /**
    * Retrieve suggestions for specified user
    * @param userId - user for which will retrieve suggestion
    * @param prefix - search prefix
    * @param limit  - limit, for result pagination
    * @return       - list of suggestions
    */
  def retrieveSuggestion(userId: UUID,
                         prefix: Option[String],
                         limit: Int = 20): Future[Seq[UserSuggestion]] =
    for {
      c <- dbColl()
      _ = logger.debug(
        s"Searching suggestion for user: $userId and prefix: $prefix...")

      // what kind of information we will receive?
//      isPublic = d :~ SHAREE_UNAME -> (d :~ "$exists" -> false)
      ownerSel = d :~ OWNER_ID -> userId
      sel = prefix.fold(ownerSel) { p =>
        d :~ "$and" ->
          Seq(d :~ "$or" -> Seq(
                d :~ SHAREE_UNAME -> BSONRegex(".*" + p.toLowerCase + ".*",
                                               "i"),
                d :~ SHAREE -> BSONRegex(".*" + p.toLowerCase + ".*", "i")
              ),
              ownerSel)
      }

      suggestions <- c
        .find(sel)
        .sort(d :~ TIMESTAMP -> -1)
        .cursor[UserSuggestion]()
        .collect[Seq](limit, Cursor.FailOnError[Seq[UserSuggestion]]())

    } yield {
      logger.debug(s"${suggestions.size} were retrieved")
      suggestions
    }

  /** For when a new email address gets registered. */
  def updateUsernamesByEmail(email: String): Future[Int] =
    for {
      c <- dbColl()
      _ = logger.debug(s"Updating sugggestion by $email...")
      mbU <- userDao.retrieveByEmail(email).map(_.flatMap(_.userData.username))

      sel = d :~ SHAREE -> BSONRegex("^" + email + "$", "i")

      n <- mbU.fold(Future.successful(0)) { u =>
        c.update(sel, d :~ "$set" -> (d :~ SHAREE_UNAME -> u), multi = true)
          .map(_.nModified)
      }
    } yield {
      logger.debug(s"$n were updated")
      n
    }

  /** For when a username gets changed. */
  def updateUsernamesByUsername(oldUsername: String,
                                newUsername: String): Future[Int] =
    for {
      c <- dbColl()
      _ = logger.debug(s"Updating username for user: $oldUsername")

      sel = d :~ SHAREE_UNAME -> BSONRegex("^" + oldUsername + "$", "i")
      upd = d :~ "$set" -> (d :~ SHAREE_UNAME -> newUsername)
      updRes <- c.update(sel, upd, multi = true)
    } yield {
      val modified = updRes.nModified
      logger.debug(s"$modified were updated")
      modified
    }
}
