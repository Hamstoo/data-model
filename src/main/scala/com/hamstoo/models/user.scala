package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.daos.UserDao
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.util.matching.Regex

/**
  * A User has a single UserData, but can have multiple social Profiles.
  * @param loginInfo    A social `providerID` (e.g. "google") and a `providerKey` (e.g. "159549211128895714598").
  * @param confirmed    Whether the user has confirmed their email address or not (only req'd for non-social login?).
  * @param email        User's email address.
  * @param passwordInfo A `hasher` (e.g. "bcrypt") and a hashed/encrypted `password`.
  * @param oAuth2Info   `accessToken`, `tokenType` (e.g. "Bearer"), and `expiresIn` (e.g. 3600).
  * @param avatarUrl    Link to an avatar image.
  */
case class Profile(
                    loginInfo: LoginInfo,
                    confirmed: Boolean,
                    email: Option[String],
                    firstName: Option[String],
                    lastName: Option[String],
                    fullName: Option[String],
                    passwordInfo: Option[PasswordInfo] = None,
                    oAuth1Info: Option[OAuth1Info] = None,
                    oAuth2Info: Option[OAuth2Info] = None,
                    avatarUrl: Option[String] = None)

object Profile {
  implicit val loginInfHandler: BSONDocumentHandler[LoginInfo] = Macros.handler[LoginInfo]
  implicit val paswdInfHandler: BSONDocumentHandler[PasswordInfo] = Macros.handler[PasswordInfo]
  implicit val auth1InfHandler: BSONDocumentHandler[OAuth1Info] = Macros.handler[OAuth1Info]
  implicit val auth2InfHandler: BSONDocumentHandler[OAuth2Info] = Macros.handler[OAuth2Info]
  implicit val profileHandler: BSONDocumentHandler[Profile] = Macros.handler[Profile]
}

/**
  * Options for the Chrome Extension.
  * @param autoSync         Automatically sync browser bookmarks to Hamstoo marks.
  * @param menuIntegration  Adds a "Mark page with Hamstoo" option to browser context (R-click) menu.
  * @param minutesActive    Automatically mark browser tabs after this many minutes being active.
  */
case class ExtensionOptions(autoSync: Option[Boolean] = None,
                            menuIntegration: Option[Boolean] = None,
                            minutesActive: Option[Int] = None)

/**
  * Base user data object.  Each User has one of these, but can have multiple linked social Profiles.
  * @param username   Publicly-displayed user "handle" or username (issue #139).
  * @param extOpts    Extension options.
  * @param tutorial   If true, the user will see the tutorial on next login.
  */
case class UserData(
                     firstName: Option[String] = None,
                     lastName: Option[String] = None,
                     username: Option[String] = None,
                     var usernameLower: Option[String] = None,
                     avatar: Option[String] = None,
                     extOpts: Option[ExtensionOptions] = None,
                     tutorial: Option[Boolean] = Some(true)) {

  usernameLower = username.map(_.toLowerCase) // impossible to set any other way

  /** Assign a username consisting of first/last name and a random number. */
  def assignUsername()(implicit userDao: UserDao, ec: ExecutionContext): Future[UserData] = {
    val startWith = firstName.getOrElse("") + lastName.getOrElse("") match {
      case User.VALID_USERNAME(alpha, _) => alpha
      case _ => Random.alphanumeric.take(6).mkString.toLowerCase
    }
    userDao.nextUsername(startWith + Random.nextInt(9999)).map(nxt => copy(username = Some(nxt)))
  }
}

/**
  * A stub version of User case class used for autosuggestion by username.
  * even username: Option[String] is filtered of empty during requests, it is used here
  * to facilitate field mappings by avoiding of option unwrapping
  * as well
  * This class is only used to get a projection of necessary fields from mark BSONDocument to optimize performance of db query
  * look at https://docs.mongodb.com/manual/tutorial/optimize-query-performance-with-indexes-and-projections/#use-projections-to-return-only-necessary-data
  */
case class UserAutosuggested(id: UUID, username: String = "")

/**
  * Finally, the full User object that is stored in the database for each user.  Notice that this class
  * extends Silhouette's Identity trait.
  * @param id        Unique ID.
  * @param userData  A single base UserData object.
  * @param profiles  A list of linked social Profiles.
  */
case class User(id: UUID, userData: UserData, profiles: List[Profile]) extends Identity {

  /** Returns the Profile corresponding to the given LoginInfo. */
  def profileFor(loginInfo: LoginInfo): Option[Profile] = profiles.find(_.loginInfo == loginInfo)

  /** Returns true if the email-address/Profile for the given LoginInfo has been confirmed. */
  def confirmed(loginInfo: LoginInfo): Boolean = profileFor(loginInfo).exists(_.confirmed)

  /** Returns a list of all of the User's *confirmed* email addresses on file. */
  def emails: Set[String] = profiles.filter(_.confirmed).flatMap(_.email).toSet

  /** Return first confirmed email address. */
  def email: Option[String] = profiles.filter(_.confirmed).flatMap(_.email).headOption

  /** Returns a @username or UUID if username is absent--useful for logging. */
  def usernameId: String = userData.username.fold(id.toString)("@" + _)
}

object User extends BSONHandlers {

  val VALID_USERNAME: Regex = raw"^([a-zA-Z][a-zA-Z0-9_]+[a-zA-Z_])([0-9]*)$$".r

  /** Creates a dummy User without anything but an ID, which is useful to have in some cases. */
  def apply(id: UUID): Option[User] = Some(User(id, UserData(), Nil))

  val ID: String = nameOf[User](_.id)
  val UDATA: String = nameOf[User](_.userData)
  val UNAME: String = nameOf[UserData](_.username)
  val UNAMELOW: String = nameOf[UserData](_.usernameLower)
  val UNAMEx: String = UDATA + "." + UNAME
  val UNAMELOWx: String = UDATA + "." + UNAMELOW
  val PROFILES: String = nameOf[User](_.profiles)
  val LINFO: String = nameOf[Profile](_.loginInfo)
  val CONF: String = nameOf[Profile](_.confirmed)
  val PSWNF: String = nameOf[Profile](_.passwordInfo)
  val OA1NF: String = nameOf[Profile](_.oAuth1Info)
  val OA2NF: String = nameOf[Profile](_.oAuth2Info)
  val PLINFOx: String = PROFILES + "." + LINFO
  val PEMAILx: String = PROFILES + "." + nameOf[Profile](_.email)
  implicit val extOptsHandler: BSONDocumentHandler[ExtensionOptions] = Macros.handler[ExtensionOptions]
  implicit val userDataHandler: BSONDocumentHandler[UserData] = Macros.handler[UserData]
  implicit val userBsonHandler: BSONDocumentHandler[User] = Macros.handler[User]
  implicit val userAutosuggestedBsonHandler: BSONDocumentHandler[UserAutosuggested] = Macros.handler[UserAutosuggested]

}




