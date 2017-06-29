package com.hamstoo.daos

import com.hamstoo.models.User
import com.hamstoo.models.User._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for users' password info. */
class MongoPasswordInfoDao(db: Future[DefaultDB]) extends DelegableAuthInfoDAO[PasswordInfo] {

  import com.hamstoo.models.Profile.{loginInfHandler, paswdInfHandler}

  private val futCol: Future[BSONCollection] = db map (_ collection "users")
  private val d = BSONDocument.empty

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
  override def add(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$set" -> (d :~ s"$PROF.$$.$PSWNF" -> authInfo))
    if wr.ok
  } yield authInfo

  /** Updates user entry's auth for a given login. */
  override def save(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] = add(loginInfo, authInfo)

  /** Updates user entry's auth for a given login. */
  override def update(loginInfo: LoginInfo, authInfo: PasswordInfo): Future[PasswordInfo] = add(loginInfo, authInfo)

  /** Removes user entry's auth for a given login. */
  override def remove(loginInfo: LoginInfo): Future[Unit] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$pull" -> (d :~ s"$PROF.loginInfo" -> loginInfo))
    if wr.ok
  } yield ()
}
