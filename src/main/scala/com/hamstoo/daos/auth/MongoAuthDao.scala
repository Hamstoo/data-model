package com.hamstoo.daos.auth

import com.hamstoo.models.User._
import com.hamstoo.models.{Profile, User}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{AuthInfo, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
  * That class define functionality for daos that works with subtypes of AuthInfo,
  * like OAuth1Info, OAuth2Info, PasswordInfo.
  */
abstract class MongoAuthDao[A <: AuthInfo: ClassTag: TypeTag](db: () => Future[DefaultDB])
                                                                      extends DelegableAuthInfoDAO[A] {

  import com.hamstoo.models.Profile._
  import com.hamstoo.utils._

  val logger: Logger
  protected def dbColl(): Future[BSONCollection] = db().map(_.collection("users"))

  /**
    * Retrive auth information, by login information, depending on type parameter A.
    * @param loginInfo - login information
    * @return - future value with option auth information
    */
  def find(loginInfo: LoginInfo): Future[Option[A]] = for {
    c <- dbColl()
    optUser <- c.find(d :~ PLINFOx -> loginInfo).one[User]
  } yield for {
    user <- optUser
    prof <- user.profiles find (_.loginInfo == loginInfo)
    oai <- getAuth(prof)
  } yield oai.asInstanceOf[A]

  /**
    * Updates user entry's auth information for a given login information.
    * @param loginInfo - login information
    * @param authInfo - Auth information
    * @return - future with added auth information
    */
  override def add(loginInfo: LoginInfo, authInfo: A): Future[A] = for {
      c <- dbColl()
      wr <- c update(d :~ PLINFOx -> loginInfo, d :~ "$set" -> produceBson(authInfo))
      _ <- wr failIfError
    } yield authInfo

  override def save(loginInfo: LoginInfo, authInfo: A): Future[A] = add(loginInfo, authInfo)

  override def update(loginInfo: LoginInfo, authInfo: A): Future[A] = add(loginInfo, authInfo)

  /**
    * Removing auth info of type A for current login info
    * @param loginInfo - login information
    * @return - empty future
    */
  override def remove(loginInfo: LoginInfo): Future[Unit] = for {
    c <- dbColl()
    wr <- c update(d :~ PLINFOx -> loginInfo, d :~ "$pull" -> (d :~ PROFILES -> (d :~ LINFO -> loginInfo)))
    _ <- wr failIfError
  } yield ()

  // get required auth by type parameter
  private def getAuth(profile: Profile): Option[AuthInfo] = {
    typeOf[A] match {
      case a1 if a1 =:= typeOf[OAuth1Info] => profile.oAuth1Info
      case a2 if a2 =:= typeOf[OAuth2Info] => profile.oAuth2Info
      case pass if pass =:= typeOf[PasswordInfo] => profile.passwordInfo
      case _ => throw new MatchError("Only instance of T <: AuthInfo can be passed")
    }
  }

  // produce BSONDocument for subtype of AuthInfo
  private def produceBson(auth: AuthInfo): BSONDocument = auth match {
    case auth1: OAuth1Info => d :~ s"$PROFILES.$$.$OA1NF" -> auth1
    case auth2: OAuth2Info => d :~ s"$PROFILES.$$.$OA2NF" -> auth2
    case pass: PasswordInfo => d :~ s"$PROFILES.$$.$PSWNF" -> pass
  }
}
