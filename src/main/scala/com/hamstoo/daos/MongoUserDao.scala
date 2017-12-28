package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.User._
import com.hamstoo.models.{Profile, User, UserGroup}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import play.api.Logger
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

  val logger: Logger = Logger(classOf[MongoUserDao])
  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.models.UserGroup.{HASH, PUBLIC_USER_GROUPS, SHROBJS, userGroupHandler, sharedObjHandler}
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
    Index(HASH -> Ascending :: Nil) % s"bin-$HASH-1" :: // MongoDB Hashed Indexes don't seem to work for this purpose
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
    _ <- Future.successful(logger.debug(s"Retrieving user $userId"))
    c <- dbColl()
    opt <- c.find(d :~ ID -> userId.toString).one[User]
  } yield {
    if (opt.isDefined) logger.debug(s"Found user $userId")
    opt
  }

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

  /**
    * Saves or updates user group with the given ID.  Optionally provide an ObjectId-TimeStamp pair to add
    * to the saved document's `sharedObjs` list.
    */
  def saveGroup(ug: UserGroup, sharedObj: Option[UserGroup.SharedObj] = None): Future[UserGroup] =
    PUBLIC_USER_GROUPS.get(ug.id).fold { // prevent accidental public save
      for {
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
    }(publicUserGroup => Future.successful(publicUserGroup))

  /** Retrieves user group either from PUBLIC_USER_GROUPS or, if not there, from the database. */
  def retrieveGroup(ugId: ObjectId): Future[Option[UserGroup]] = PUBLIC_USER_GROUPS.get(ugId).fold {
    logger.debug(s"Retrieving user group $ugId")
    for {
      c <- groupColl()
      opt <- c.find(d :~ ID -> ugId).one[UserGroup]
    } yield {
      if (opt.isDefined) logger.debug(s"Found user group $ugId")
      opt
    }
  }(publicUserGroup => Future.successful(Some(publicUserGroup)))

  /** Retrieves a user group from the database first based on its hash, then on its hashed fields. */
  protected def retrieveGroup(ug: UserGroup): Future[Option[UserGroup]] = for {
    c <- groupColl()
    found <- c.find(d :~ HASH -> UserGroup.hash(ug)).coll[UserGroup, Seq]()
  } yield found.find(x => x.userIds == ug.userIds && x.emails == ug.emails)

  /** Removes user group given ID. */
  def deleteGroup(groupId: ObjectId): Future[Unit] = for {
    c <- groupColl()
    wr <- c.remove(d :~ ID -> groupId)
    _ <- wr.failIfError
  } yield ()
}
