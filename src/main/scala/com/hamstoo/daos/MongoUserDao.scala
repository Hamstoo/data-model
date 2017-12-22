package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.User._
import com.hamstoo.models.{Profile, User, UserGroup}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for user accounts.
  */
class MongoUserDao(db: () => Future[DefaultDB]) extends IdentityService[User] {

  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.models.UserGroup.{PUBLIC_USER_GROUPS, userGroupHandler}
  import com.hamstoo.utils._

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private def dbColl(): Future[BSONCollection] = db().map(_ collection "users")
  private def groupColl(): Future[BSONCollection] = db().map(_ collection "usergroups")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(PLGNF -> Ascending :: Nil, unique = true) % s"bin-$PLGNF-1-uniq" ::
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(s"$PROF.$EMAIL" -> Ascending :: Nil) % s"bin-$PROF.$EMAIL-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 323 seconds)

  private val groupIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Nil toMap;
  Await.result(groupColl().map(_.indexesManager.ensure(groupIndxs)), 223 seconds)

  /** Saves or updates user account data by matching provided `User`'s `.id`. */
  def save(u: User): Future[Unit] = for {
    c <- dbColl()
    wr <- c.update(d :~ ID -> u.id.toString, u, upsert = true)
    _ <- wr.failIfError
  } yield ()

  /** Retrieves user account data by login. */
  def retrieve(loginInfo: LoginInfo): Future[Option[User]] = for {
    c <- dbColl()
    opt <- c.find(d :~ PLGNF -> loginInfo).one[User]
  } yield opt

  /** Retrieves user account data by user id. */
  def retrieve(userId: UUID): Future[Option[User]] = for {
    c <- dbColl()
    opt <- c.find(d :~ ID -> userId.toString).one[User]
  } yield opt

  /** Retrieves user account data by email. */
  def retrieve(email: String): Future[Option[User]] = for {
    c <- dbColl()
    opt <- c.find(d :~ s"$PROF.$EMAIL" -> email).one[User]
  } yield opt

  /** Attaches provided `Profile` to user account by user id. */
  def link(userId: UUID, profile: Profile): Future[User] = for {
    c <- dbColl()
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, d :~ "$push" -> (d :~ PROF -> profile), fetchNewObject = true)
  } yield wr.result[User].get

  /** Detaches provided login from user account by id. */
  def unlink(userId: UUID, loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$pull" -> (d :~ s"$PROF" -> (d :~ s"$LGNF" -> loginInfo))
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Updates one of user account's profiles by login. */
  def update(profile: Profile): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROF.$$" -> profile)
    wr <- c.findAndUpdate(d :~ PLGNF -> profile.loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Sets one of user account profiles to 'confirmed' by login. */
  def confirm(loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROF.$$.$CONF" -> true)
    wr <- c.findAndUpdate(d :~ PLGNF -> loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Removes user account by id. */
  def delete(userId: UUID): Future[Unit] = for {
    c <- dbColl()
    wr <- c.remove(d :~ ID -> userId.toString)
    _ <- wr.failIfError
  } yield ()

  /** Saves or updates user group with the given ID. */
  def saveGroup(ug: UserGroup): Future[Unit] = for {
    c <- groupColl()
    wr <- c.update(d :~ ID -> ug.id, ug, upsert = true)
    _ <- wr.failIfError
  } yield ()

  /** Retrieves user group either from PUBLIC_USER_GROUPS or, if not there, from the database. */
  def retrieveGroup(groupId: String): Future[Option[UserGroup]] = PUBLIC_USER_GROUPS.get(groupId).fold {
    for {
      c <- groupColl()
      opt <- c.find(d :~ ID -> groupId).one[UserGroup]
    } yield opt
  }(publicUserGroup => Future.successful(Some(publicUserGroup)))

  /** Removes user group given ID. */
  def deleteGroup(groupId: String): Future[Unit] = for {
    c <- groupColl()
    wr <- c.remove(d :~ ID -> groupId)
    _ <- wr.failIfError
  } yield ()
}
