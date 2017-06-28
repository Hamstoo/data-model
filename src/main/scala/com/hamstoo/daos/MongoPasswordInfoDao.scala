package com.hamstoo.daos

import com.hamstoo.models.User
import com.hamstoo.models.User._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for users' password info. */
class MongoPasswordInfoDao(db: Future[DefaultDB]) extends DelegableAuthInfoDAO[PasswordInfo] {

  import com.hamstoo.models.Profile.{loginInfHandler, paswdInfHandler}
  import com.hamstoo.utils.digestWriteResult

  private val futCol: Future[BSONCollection] = db map (_ collection "users")
  private val d = BSONDocument.empty
  /* Ensure mongo collection has proper index: */
  futCol map (_.indexesManager ensure Index(PLGNF -> Ascending :: Nil))

  /** Retrieves password info for a given login. */
  def find(loginInfo: LoginInfo): Future[Option[PasswordInfo]] = for {
    c <- futCol
    optUser <- c.find(d :~ PLGNF -> loginInfo).one[User]
  } yield for {
    user <- optUser
    profile <- user.profiles find (_.loginInfo == loginInfo)
    passInf <- profile.passwordInfo
  } yield passInf

  /** Updates user entry's auth for a given login. */
  def add(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[Either[String, PasswordInfo]] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$set" -> (d :~ s"$PROF.$$.$PSWNF" -> authInfo))
  } yield digestWriteResult(wr, authInfo)

  /** Updates user entry's auth for a given login. */
  def save(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[Either[String, PasswordInfo]] =
    add(loginInfo, authInfo)

  /** Updates user entry's auth for a given login. */
  def update(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[Either[String, PasswordInfo]] =
    add(loginInfo, authInfo)

  /** Removes user entry's auth for a given login. */
  def remove(loginInfo: LoginInfo): Future[Either[String, LoginInfo]] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$pull" -> (d :~ s"$PROF.loginInfo" -> loginInfo))
  } yield digestWriteResult(wr, loginInfo)
}
