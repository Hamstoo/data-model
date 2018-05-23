package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserSuggestion._
import com.hamstoo.models.UserSuggestion
import com.hamstoo.utils._
import reactivemongo.api.DefaultDB
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONRegex

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Provide methods for operation with username-suggestion collection
  */
class UserSuggestionDao @Inject()(implicit val db: () => Future[DefaultDB], userDao: UserDao)
    extends Dao("user_suggestion") {

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(US_OWNER_ID -> Ascending :: US_SHAREE -> Ascending :: Nil, unique = true) %
      s"bin-$US_OWNER_ID-1-$US_SHAREE-1-uniq" ::
    Index(US_SHAREE_UNAME -> Ascending :: Nil) % s"bin-$US_SHAREE_UNAME-1" ::
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

    sel = d :~ US_OWNER_ID -> ownerUserId :~
               sharee.fold(d :~ US_SHAREE -> (d :~ "$exists" -> 0))(u => d :~ US_SHAREE -> u)

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
    sel = d :~ US_OWNER_ID -> ownerUserId :~
               sharee.fold(d :~ US_SHAREE -> (d :~ "$exists" -> false))(u => d :~ US_SHAREE -> BSONRegex(s"^$u$$", "i"))
    wr <- c.remove(sel)
    _ <- wr.failIfError
  } yield {
    logger.debug(s"Removed ${wr.n} user suggestions")
    ()
  }

  /**
    * Retrieve search suggestion.
    * @param shareeUserId  current user (used as sharee in this function)
    * @return              sequence of mark owner usernames starting with prefix
    */
  def searchSuggestions(shareeUserId: UUID, ownerUsernamePrefix: String): Future[Seq[String]] = for {
    c <- dbColl()
    _ = logger.debug(s"searchSuggestions($shareeUserId, $ownerUsernamePrefix)")

    mbShareeUsername <- userDao.retrieveById(shareeUserId).map(_.flatMap(_.userData.usernameLower))

    // be sure not to use US_SHAREE here in order to user proper index for search
    isPublic = d :~ US_SHAREE_UNAME -> (d :~ "$exists" -> false)

    // either shared-to the current user directly or completely public
    shareeSel = mbShareeUsername.fold(isPublic) { unl =>
      d :~ "$or" -> Seq(d :~ US_SHAREE_UNAME -> BSONRegex("^" + unl + "$", "i"), isPublic)
    }

    ownerSel = if (ownerUsernamePrefix.isEmpty) d
               else d :~ US_OWNER_UNAME -> BSONRegex("^" + ownerUsernamePrefix + ".*", "i")

    seq <- c.find(shareeSel :~ ownerSel).coll[UserSuggestion, Seq]()

  } yield {
    logger.debug(s"searchSuggestions retrieved ${seq.size} documents")
    val now = TIME_NOW

    // put those shared directly from first, then public (note the negative symbol)
    seq.map(us => us.ownerUsername -> -(us.sharee.fold(0L)(_ => now) + us.ts))
      .groupBy(_._1).toSeq
      .sortBy(_._2.map(_._2).min)
      .map(_._1)
      .take(30)
  }
}
