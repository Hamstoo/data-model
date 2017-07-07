package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.User._
import com.hamstoo.models.{Profile, User}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for user accounts. */
class MongoUserDao(db: Future[DefaultDB]) extends IdentityService[User] {

  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedWriteResult}

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private val futCol: Future[BSONCollection] = db map (_ collection "users")
  private val d = BSONDocument.empty
  /* Ensure mongo collection has proper indexes: */
  private val indxs: Map[String, Index] =
    Index(PLGNF -> Ascending :: Nil, unique = true) % s"bin-$PLGNF-1-uniq" ::
      Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
      Index(s"$PROF.$EMAIL" -> Ascending :: Nil) % s"bin-$PROF.$EMAIL-1" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /** Saves or updates user account data by matching provided `User`'s `.id`. */
  def save(u: User): Future[Unit] = for {
    c <- futCol
    wr <- c update(d :~ ID -> u.id.toString, u, upsert = true)
    _ <- wr failIfError
  } yield ()

  /** Retrieves user account data by login. */
  def retrieve(loginInfo: LoginInfo): Future[Option[User]] = for {
    c <- futCol
    optUsr <- c.find(d :~ PLGNF -> loginInfo).one[User]
  } yield optUsr

  /** Retrieves user account data by user id. */
  def retrieve(userId: UUID): Future[Option[User]] = for {
    c <- futCol
    optUsr <- c.find(d :~ ID -> userId.toString).one[User]
  } yield optUsr

  /** Retrieves user account data by email. */
  def retrieve(email: String): Future[Option[User]] = for {
    c <- futCol
    optUsr <- c.find(d :~ s"$PROF.$EMAIL" -> email).one[User]
  } yield optUsr

  /** Attaches provided `Profile` to user account by user id. */
  def link(userId: UUID, profile: Profile): Future[User] = for {
    c <- futCol
    wr <- c findAndUpdate(d :~ ID -> userId.toString, d :~ "$push" -> (d :~ PROF -> profile), fetchNewObject = true)
  } yield wr.result[User].get

  /** Detaches provided login from user account by id. */
  def unlink(userId: UUID, loginInfo: LoginInfo): Future[User] = for {
    c <- futCol
    upd = d :~ "$pull" -> (d :~ s"$PROF.$LGNF" -> loginInfo)
    wr <- c findAndUpdate(d :~ ID -> userId.toString, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Updates one of user account's profiles by login. */
  def update(profile: Profile): Future[User] = for {
    c <- futCol
    upd = d :~ "$set" -> (d :~ s"$PROF.$$" -> profile)
    wr <- c findAndUpdate(d :~ PLGNF -> profile.loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Sets one of user account profiles to 'confirmed' by login. */
  def confirm(loginInfo: LoginInfo): Future[User] = for {
    c <- futCol
    upd = d :~ "$set" -> (d :~ s"$PROF.$$.$CONF" -> true)
    wr <- c findAndUpdate(d :~ PLGNF -> loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Removes user account by id. */
  def delete(userId: UUID): Future[Unit] = for {
    c <- futCol
    wr <- c remove d :~ ID -> userId.toString
    _ <- wr failIfError
  } yield ()
}
