package com.hamstoo.daos.auth

import com.hamstoo.models.User._
import com.hamstoo.models.{Profile, User}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import play.api.Logger
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

abstract class MongoAuthDao[A <: AuthInfo: ClassTag: TypeTag](coll: Future[BSONCollection], log: Logger) extends DelegableAuthInfoDAO[A] {

  import com.hamstoo.models.Profile._
  import com.hamstoo.utils._

  /** Retrieves auth for a given login. */
  def find(loginInfo: LoginInfo): Future[Option[A]] = for {
    c <- coll
    optUser <- c.find(d :~ PLGNF -> loginInfo).one[User]
  } yield for {
    user <- optUser
    prof <- user.profiles find (_.loginInfo == loginInfo)
    oai <- getAuth(prof)
  } yield oai.asInstanceOf[A]

  /** Updates user entry's auth for a given login. */
  override def add(loginInfo: LoginInfo, authInfo: A): Future[A] = for {
      c <- coll
      wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$set" -> produceBson(authInfo))
      _ <- wr failIfError
    } yield authInfo

  /** Updates user entry's auth for a given login. */
  override def save(loginInfo: LoginInfo, authInfo: A): Future[A] = add(loginInfo, authInfo)

  /** Updates user entry's auth for a given login. */
  override def update(loginInfo: LoginInfo, authInfo: A): Future[A] = add(loginInfo, authInfo)

  /** Removes user entry's auth for a given login. */
  override def remove(loginInfo: LoginInfo): Future[Unit] = for {
    c <- coll
    wr <- c update(d :~ PLGNF -> loginInfo, d :~ "$pull" -> (d :~ PROF -> (d :~ "loginInfo" -> loginInfo)))
    _ <- wr failIfError
  } yield ()

  private def getAuth(profile: Profile): Option[AuthInfo] = {
    typeOf[A] match {
      case a1 if a1 =:= typeOf[OAuth1Info] => profile.oAuth1Info
      case a2 if a2 =:= typeOf[OAuth2Info] => profile.oAuth2Info
      case pass if pass =:= typeOf[PasswordInfo] => profile.passwordInfo
      case _ => throw new MatchError("Only instance of T <: AuthInfo can be passed")
    }
  }

  private def produceBson(auth: AuthInfo): BSONDocument = auth match {
    case auth1: OAuth1Info => d :~ s"$PROF.$$.$OA1NF" -> auth1
    case auth2: OAuth2Info => d :~ s"$PROF.$$.$OA2NF" -> auth2
    case pass: PasswordInfo => d :~ s"$PROF.$$.$PSWNF" -> pass
  }
}
