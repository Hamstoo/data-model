/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.User._
import com.hamstoo.models.{Mark, Profile, Shareable, SharedWith, User, UserGroup}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object UserDao {
  var migrateData: Boolean = scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)
}

/**
  * Data access object for user accounts.  Note that this class extends IdentityService.
  */
@Singleton
class UserDao @Inject()(implicit db: () => Future[DefaultDB]) extends IdentityService[User] {

  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.models.UserGroup.{HASH, SHROBJS, userGroupHandler, sharedObjHandler, sharedWithHandler}
  import com.hamstoo.models.Shareable.SHARED_WITH
  import com.hamstoo.utils._

  val logger: Logger = Logger(classOf[UserDao])

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private def dbColl(): Future[BSONCollection] = db().map(_ collection "users")
  private def groupColl(): Future[BSONCollection] = db().map(_ collection "usergroups")
  private def marksColl(): Future[BSONCollection] = db().map(_ collection "entries")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(PLINFOx -> Ascending :: Nil, unique = true) % s"bin-$PLINFOx-1-uniq" ::
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(PEMAILx -> Ascending :: Nil) % s"bin-$PEMAILx-1" ::
    Index(UNAMELOWx -> Ascending :: Nil, unique = true) % s"bin-$UNAMELOWx-1-uniq" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 323 seconds)

  private val groupIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(HASH -> Ascending :: Nil) % s"bin-$HASH-1" :: // MongoDB Hashed Indexes don't seem to work for this purpose
    Nil toMap;
  Await.result(groupColl().map(_.indexesManager.ensure(groupIndxs)), 223 seconds)

  /** Saves or updates user account data by matching provided `User`'s `.id`. */
  def save(u: User): Future[Unit] = for {
    c <- dbColl()
    wr <- c.update(d :~ ID -> u.id.toString, u, upsert = true)
    _ <- wr.failIfError
  } yield ()

  /** Start with a username, but then return a different one if that one is already taken. */
  def nextUsername(startWith: String): Future[String] = {
    retrieveByUsername(startWith).flatMap { _.fold(Future.successful(startWith)) { _ =>
      val User.VALID_USERNAME(alpha, numeric) = startWith
      val number = if (numeric.isEmpty) 2 else numeric.toInt + 1
      nextUsername(alpha + number)
    }}
  }

  /** Retrieves user account data by login. */
  def retrieve(loginInfo: LoginInfo): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ PLINFOx -> loginInfo).one[User])

  /** Retrieves user account data by user id. */
  def retrieveById(userId: UUID): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ ID -> userId.toString).one[User])

  /** Retrieves user account data by email. */
  def retrieveByEmail(email: String): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ PEMAILx -> email).one[User])

  /** Retrieves user account data by username. */
  def retrieveByUsername(username: String): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ UNAMELOWx -> username.toLowerCase).one[User])

  /** Attaches provided `Profile` to user account by user id. */
  def link(userId: UUID, profile: Profile): Future[User] = for {
    c <- dbColl()
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, d :~ "$push" -> (d :~ PROFILES -> profile), fetchNewObject = true)
  } yield wr.result[User].get

  /** Detaches provided login from user account by id. */
  def unlink(userId: UUID, loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$pull" -> (d :~ PROFILES -> (d :~ LINFO -> loginInfo))
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Updates one of user account's profiles by login. */
  def update(profile: Profile): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROFILES.$$" -> profile)
    wr <- c.findAndUpdate(d :~ PLINFOx -> profile.loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Sets one of user account profiles to 'confirmed' by login. */
  def confirm(loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROFILES.$$.$CONF" -> true)
    wr <- c.findAndUpdate(d :~ PLINFOx -> loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Removes user account by id. */
  def delete(userId: UUID): Future[Unit] = for {
    c <- dbColl()
    wr <- c.remove(d :~ ID -> userId.toString)
    _ <- wr.failIfError
  } yield ()

  /**
    * Saves or updates user group with the given ID.  Optionally provide an ObjectId-TimeStamp pair to add
    * to the saved document's `sharedObjs` list.
    */
  def saveGroup(ug: UserGroup, sharedObj: Option[UserGroup.SharedObj] = None): Future[UserGroup] = for {
    c <- groupColl()
    existing <- retrieveGroup(ug)

    // be sure to insert, not upsert, here b/c we want to avoid overwriting any existing sharedObjs history
    wr <- if (existing.isEmpty) c.insert(ug) else
      c.update(d :~ ID -> existing.get.id, d :~ "$push" -> (d :~ SHROBJS -> (d :~ "$each" -> ug.sharedObjs)))
    _ <- wr.failIfError

    // optionally update the UserGroup's list of objects it was used to share
    _ <- if (sharedObj.isEmpty) Future.successful {} else
      c.update(d :~ ID -> existing.fold(ug.id)(_.id), d :~ "$push" -> (d :~ SHROBJS -> sharedObj.get))

  } yield existing.getOrElse(ug)

  /** Retrieves user group either from PUBLIC_USER_GROUPS or, if not there, from the database. */
  def retrieveGroup(ugId: ObjectId): Future[Option[UserGroup]] = for {
    c <- groupColl()
    _ = logger.debug(s"Retrieving user group $ugId")
    opt <- c.find(d :~ ID -> ugId).one[UserGroup]
  } yield {
    if (opt.isDefined) logger.debug(s"Found user group $ugId")
    opt
  }

  /**
    * Retrieves a user group from the database first based on its hash, then on its hashed fields. This allows
    * for the prevention of UserGroup duplicates.
    */
  protected def retrieveGroup(ug: UserGroup): Future[Option[UserGroup]] = for {
    c <- groupColl()
    found <- c.find(d :~ HASH -> UserGroup.hash(ug)).coll[UserGroup, Seq]()
  } yield found.find(x => x.userIds == ug.userIds && x.emails == ug.emails)

  /** Retrieve a list of usernames and email addresses that the given user has shared with, in that order. */
  def retrieveRecentSharees(userId: UUID): Future[Seq[String]] = for {

    // first fetch the SharedWiths from the given user's (current) marks
    cMarks <- marksColl()
    sel = d :~ Mark.USR -> userId :~ curnt :~ SHARED_WITH -> (d :~ "$exists" -> 1)
    prj = d :~ Shareable.SHARED_WITH -> 1 :~ "_id" -> 0
    sharedWiths <- cMarks.find(sel, prj).coll[BSONDocument, Seq]()

    // traverse down through the data model hierarchy to get UserGroup IDs mapped to their most recent time stamps
    ugIds = sharedWiths.flatMap(_.getAs[SharedWith](SHARED_WITH).map { sw =>
      Seq(sw.readOnly, sw.readWrite).flatten.flatMap(_.group.map(_ -> sw.ts))
    })
    ug2TimeStamp = ugIds.flatten.groupBy(_._1).mapValues(_.map(_._2).max)

    // lookup the UserGroups given their IDs ("application-level join")
    cGroup <- groupColl()
    ugs <- cGroup.find(d :~ ID -> (d :~ "$in" -> ug2TimeStamp.keys)).coll[UserGroup, Seq]()
    shareeUserIds = ugs.flatMap(_.userIds).flatten.toSet

    // get all the usernames of the shared-with users ("sharees")
    cUsers <- dbColl()
    sharees <- cUsers.find(d :~ User.ID -> (d :~ "$in" -> shareeUserIds)).coll[User, Seq]()
    shareeId2Username = sharees.flatMap(u => u.userData.username.map(u.id -> _)).toMap

  } yield {

    // combine usernames and emails of shared-with people into a single collection of "sharee" strings
    val sharee2TimeStamp = ugs.flatMap { ug =>
      val shareeStrings = ug.emails.getOrElse(Set.empty[String]) ++
                          ug.userIds.fold(Set.empty[String])(_.flatMap(shareeId2Username.get).map("@" + _))
      shareeStrings.map(_ -> ug2TimeStamp(ug.id))
    }

    // map each sharee to its most recent usage, sort descending, and then return the most recent 50
    sharee2TimeStamp.groupBy(_._1).mapValues(_.map(_._2).max).toSeq.sortBy(-_._2).map(_._1).take(50)
  }

  /** Removes user group given ID. */
  def deleteGroup(groupId: ObjectId): Future[Unit] = for {
    c <- groupColl()
    wr <- c.remove(d :~ ID -> groupId)
    _ <- wr.failIfError
  } yield ()
}