package com.hamstoo.daos

import com.hamstoo.models.User
import com.hamstoo.models.User._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for users' auth tokens. */
class MongoOAuth2InfoDao(db: Future[DefaultDB]) extends DelegableAuthInfoDAO[OAuth2Info] {

  import com.hamstoo.models.Profile.{auth2InfHandler, loginInfHandler}
  import com.hamstoo.utils.digestWriteResult

  private val futCol: Future[BSONCollection] = db map (_ collection "users")
  private val d = BSONDocument.empty
  /* Ensure mongo collection has proper index: */
  futCol map (_.indexesManager ensure Index(PLGNF -> Ascending :: Nil))

  /** Retrieves auth for a given login. */
  def find(loginInfo: LoginInfo): Future[Option[OAuth2Info]] = for {
    c <- futCol
    optUser <- c.find(d :~ PLGNF -> loginInfo).one[User]
  } yield for {
    user <- optUser
    prof <- user.profiles find (_.loginInfo == loginInfo)
    oai <- prof.oAuth2Info
  } yield oai

  /** Updates user entry's auth for a given login. */
  def add(loginInfo: LoginInfo, authInfo: OAuth2Info): Future[Either[String, OAuth2Info]] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$set" -> (d :~ s"$PROF.$$.$OA1NF" -> authInfo))
  } yield digestWriteResult(wr, authInfo)

  /** Updates user entry's auth for a given login. */
  def save(loginInfo: LoginInfo, authInfo: OAuth2Info): Future[Either[String, OAuth2Info]] = add(loginInfo, authInfo)

  /** Updates user entry's auth for a given login. */
  def update(loginInfo: LoginInfo, authInfo: OAuth2Info): Future[Either[String, OAuth2Info]] = add(loginInfo, authInfo)

  /** Removes user entry's auth for a given login. */
  def remove(loginInfo: LoginInfo): Future[Either[String, LoginInfo]] = for {
    c <- futCol
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$pull" -> (d :~ PROF -> (d :~ "loginInfo" -> loginInfo)))
  } yield digestWriteResult(wr, loginInfo)
}
