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
  import com.hamstoo.utils.digestWriteResult

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private val futCol: Future[BSONCollection] = db map (_ collection "users")
  private val d = BSONDocument.empty
  /* Ensure the mongo collection has proper indexes: */
  for {
    c <- futCol
    im = c.indexesManager
  } {
    im ensure Index(PLGNF -> Ascending :: Nil)
    im ensure Index(ID -> Ascending :: Nil)
    im ensure Index(s"$PROF.$EMAIL" -> Ascending :: Nil)
  }

  /** Saves or updates user account data by matching provided `User`'s `.id`. */
  def save(u: User): Future[Either[String, User]] = for {
    c <- futCol
    wr <- c update(d :~ ID -> u.id.toString, u, upsert = true)
  } yield digestWriteResult(wr, u)

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
  def link(userId: UUID, profile: Profile): Future[Either[String, User]] = for {
    c <- futCol
    wr <- c findAndUpdate(d :~ ID -> userId.toString, d :~ "$push" -> (d :~ PROF -> profile))
  } yield if (wr.lastError.isDefined || wr.value.isEmpty) Left(wr.lastError.flatMap(_.err) getOrElse "")
  else Right(wr.value.get.as[User])

  /** Detaches provided login from user account by id. */
  def unlink(userId: UUID, loginInfo: LoginInfo): Future[Either[String, User]] = for {
    c <- futCol
    wr <- c findAndUpdate(d :~ ID -> userId.toString, d :~ "$pull" -> (d :~ s"$PROF.$LGNF" -> loginInfo))
  } yield if (wr.lastError.isDefined || wr.value.isEmpty) Left(wr.lastError.flatMap(_.err) getOrElse "")
  else Right(wr.value.get.as[User])

  /** Updates one of user account's profiles by login. */
  def update(profile: Profile): Future[Either[String, User]] = for {
    c <- futCol
    wr <- c findAndUpdate(d :~ PLGNF -> profile.loginInfo, d :~ "$set" -> (d :~ s"$PROF.$$" -> profile))
  } yield if (wr.lastError.isDefined || wr.value.isEmpty) Left(wr.lastError.flatMap(_.err) getOrElse "")
  else Right(wr.value.get.as[User])

  /** Sets one of user account profiles to 'confirmed' by login. */
  def confirm(loginInfo: LoginInfo): Future[Either[String, User] with Product with Serializable] = for {
    c <- futCol
    wr <- c findAndUpdate(d :~ PLGNF -> loginInfo, d :~ "$set" -> (d :~ s"$PROF.$$.$CONF" -> true))
  } yield if (wr.lastError.isDefined || wr.value.isEmpty) Left(wr.lastError.flatMap(_.err) getOrElse "")
  else Right(wr.value.get.as[User])

  /** Removes user account by id. */
  def delete(userId: UUID): Future[Either[String, UUID]] = for {
    c <- futCol
    wr <- c remove d :~ ID -> userId.toString
  } yield digestWriteResult(wr, userId)
}
