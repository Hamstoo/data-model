/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.models.UserSuggestion
import com.hamstoo.utils._
import reactivemongo.api.DefaultDB
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONRegex}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

/**
  * Provide methods for operation with username-suggestion collection
  */
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB], userDao: UserDao)
    extends Dao("usersuggestions") {

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(OWNER_ID -> Ascending :: SHAREE -> Ascending :: Nil, unique = true) %
      s"bin-$OWNER_ID-1-$SHAREE-1-uniq" ::
    Index(OWNER_UNAME -> Ascending :: Nil) % s"bin-$OWNER_UNAME-1" ::
    Index(SHAREE -> Ascending :: Nil) % s"bin-$SHAREE-1" ::
    Index(SHAREE_UNAME -> Ascending :: Nil) % s"bin-$SHAREE_UNAME-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 274 seconds)

  /**
    * Save new user suggestion to collection and/or update `shareeUsername` and `ts` of existing.
    * @param ownerUserId  owner user identifier
    * @param sharee  optional username or email address of sharee (share recipient)
    * @return        upserted user suggestion
    */
  def save(ownerUserId: UUID, sharee: Option[String]): Future[UserSuggestion] = for {
    c <- dbColl()
    us <- UserSuggestion.xapply(ownerUserId, sharee) // construct UserSuggestion with updated `shareeUsername` and `ts`
    _ = logger.debug(s"Saving user suggestion: $us")

    sel = d :~ OWNER_ID -> ownerUserId :~
               sharee.fold(d :~ SHAREE -> (d :~ "$exists" -> 0))(u => d :~ SHAREE -> u)

    wr <- c.update(sel, us, upsert = true)
    _ <- wr.failIfError
    if wr.n == 1 // don't use nModified here b/c we're upserting, not updating

  } yield {
    logger.debug("User suggestion saved")
    us
  }

  /** Removes an existing user suggestion.  Probably should only be used when sharee==None. */
  def delete(ownerUserId: UUID, sharee: Option[String] = None): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Removing user suggestion: $ownerUserId / $sharee")
    sel = d :~ OWNER_ID -> ownerUserId :~
               sharee.fold(d :~ SHAREE -> (d :~ "$exists" -> false))(u => d :~ SHAREE -> BSONRegex(s"^$u$$", "i"))
    wr <- c.delete[BSONDocument](ordered = false).one(sel)
    _ <- wr.failIfError
  } yield {
    logger.debug(s"Removed ${wr.n} user suggestions")
    ()
  }

  /**
    * Retrieve search suggestion.
    * @param mbShareeUserId  current user (used as sharee in this function)
    * @return                sequence of mark owner usernames starting with prefix
    */
  def retrieveUserSuggestions(mbShareeUserId: Option[UUID], ownerUsernamePrefix: String): Future[Seq[String]] = for {
    c <- dbColl()
    _ = logger.debug(s"retrieveUserSuggestions($mbShareeUserId, $ownerUsernamePrefix)")

    mbShareeUsername <- mbShareeUserId.fold(Future.successful(Option.empty[String])) { id =>
                          userDao.retrieveById(id).map(_.flatMap(_.userData.usernameLower)) }

    // be sure not to use SHAREE here in order to user proper index for search
    isPublic = d :~ SHAREE_UNAME -> (d :~ "$exists" -> false)

    // either shared-to the current user directly or completely public
    shareeSel = mbShareeUsername.fold(isPublic) { unl =>
                  d :~ "$or" -> Seq(d :~ SHAREE_UNAME -> BSONRegex("^" + unl + "$", "i"), isPublic) }

    ownerSel = if (ownerUsernamePrefix.isEmpty) d
               else d :~ OWNER_UNAME -> BSONRegex("^" + ownerUsernamePrefix + ".*", "i")

    seq <- c.find(shareeSel :~ ownerSel, Option.empty[UserSuggestion]).coll[UserSuggestion, Seq]()

  } yield {
    logger.debug(s"retrieveUserSuggestions retrieved ${seq.size} documents")
    val now = TIME_NOW

    // put those shared directly from first, then public (note the negative symbol)
    seq.map(us => us.ownerUsername -> -(us.sharee.fold(0L)(_ => now) + us.ts))
      .groupBy(_._1).toSeq
      .sortBy(_._2.map(_._2).min)
      .map(_._1)
      .take(30)
  }

  /** For when a new email address gets registered. */
  def updateUsernamesByEmail(email: String): Future[Int] = for {
    c <- dbColl()
    mbU <- userDao.retrieveByEmail(email).map(_.headOption.flatMap(_.userData.username))
    n <- mbU.fold(Future.successful(0)){ u =>
      c.update(d :~ SHAREE -> BSONRegex("^" + email + "$", "i"),
               d :~ "$set" -> (d :~ SHAREE_UNAME -> u),
               multi = true).map(_.nModified)
    }
  } yield n

  /** For when a username gets changed. */
  def updateUsernamesByUsername(oldUsername: String, newUsername: String): Future[Int] = for {
    c <- dbColl()
    doUpdate = (field: String) => c.update(d :~ field -> BSONRegex("^" + oldUsername + "$", "i"),
                                           d :~ "$set" -> (d :~ field -> newUsername),
                                           multi = true).map(_.nModified)
    fn0 = doUpdate(OWNER_UNAME) // launch future
    n1 <- doUpdate(SHAREE_UNAME) // don't wait, just launch again (though using same DefaultDB so perhaps has no effect)
    n0 <- fn0
  } yield n0 + n1
}
