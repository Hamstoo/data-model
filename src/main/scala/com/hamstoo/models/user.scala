package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.daos.UserDao
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import play.api.libs.json.{Json, OFormat}
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
case class Profile(loginInfo: LoginInfo,
                   confirmed: Boolean,
                   email: Option[String],
                   firstName: Option[String],
                   lastName: Option[String],
                   fullName: Option[String],
                   passwordInfo: Option[PasswordInfo] = None,
                   oAuth1Info: Option[OAuth1Info] = None,
                   oAuth2Info: Option[OAuth2Info] = None,
                   avatarUrl: Option[String] = None,
                   created: Option[TimeStamp] = Some(TIME_NOW))

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
  * @param username       Publicly-displayed user "handle" or username (issue #139).
  * @param extOpts        Extension options.
  * @param tutorial       If true, the user will see the tutorial on next login.
  * @param markItTooltip  If true, the user will see a tooltip when hovering over the "Mark It" button.
  */
case class UserData(firstName: Option[String] = None,
                    lastName: Option[String] = None,
                    username: Option[String] = None,
                    var usernameLower: Option[String] = None,
                    avatar: Option[String] = None,
                    extOpts: Option[ExtensionOptions] = None,
                    tutorial: Option[Boolean] = Some(true),
                    markItTooltip: Option[Boolean] = Some(true),
                    created: Option[TimeStamp] = Some(TIME_NOW)) {

  usernameLower = username.map(_.toLowerCase) // impossible to set any other way

  /** Generate a username consisting of first/last name and a random number. */
  def generateUsername()(implicit userDao: UserDao, ec: ExecutionContext): Future[String] = {
    val startWith = (firstName.getOrElse("") + lastName.getOrElse("")).replaceAll(User.NON_USERNAME_CHARS, "") match {
      case User.VALID_USERNAME(alpha, _) => alpha
      case _ => "u" + Random.alphanumeric.take(6).mkString.toLowerCase
    }
    assert(User.VALID_USERNAME.pattern.matcher(startWith).matches) // must match to avoid infinite recursive loop
    userDao.nextUsername(startWith + Random.nextInt(9999), Some(this))
  }
}

/**
  * Issue #364: If user repeatedly deletes automarks from a certain domain, then stop automarking that domain.
  * @param domain  The automarked domain name.
  * @param n  The number of timesa automarks of this domain have been deleted.
  */
case class DomainAutomarkDeleteCount(domain: String, n: Int = 0) {
  def canBeAutomarked: Boolean = n < DomainAutomarkDeleteCount.MAX_AUTOMARK_DOMAIN_DELETES
}

object DomainAutomarkDeleteCount {
  val MAX_AUTOMARK_DOMAIN_DELETES = 5
}

/**
  * Finally, the full User object that is stored in the database for each user.  Notice that this class
  * extends Silhouette's Identity trait.
  * @param id        Unique ID.
  * @param userData  A single base UserData object.
  * @param profiles  A list of linked social Profiles.
  * @param domainAutomarkDeleteCounts0  Sequence of domains that were automarked but then deleted and a deletion count.
  * @param fbLiked  True if user Liked our site, false if user chose to 'Skip' Liking our site, None if user
  *                 has not yet been queried by facebook-like-modal.html and forced to choose between the two.
  */
case class User(id: UUID,
                userData: UserData,
                profiles: List[Profile],
                domainAutomarkDeleteCounts0: Option[Seq[DomainAutomarkDeleteCount]] = None,
                fbLiked: Option[Boolean] = None) extends Identity {

  /** Returns the Profile corresponding to the given LoginInfo. */
  def profileFor(loginInfo: LoginInfo): Option[Profile] = profiles.find(_.loginInfo == loginInfo)

  /** Returns a list of the provider IDs of this user; convenience function. */
  def providers: Seq[String] = profiles.map(_.loginInfo.providerID)

  /** Returns true if the email-address/Profile for the given LoginInfo has been confirmed. */
  def confirmed(loginInfo: LoginInfo): Boolean = profileFor(loginInfo).exists(_.confirmed)

  /** Returns a list of all of the User's *confirmed* email addresses on file. */
  def emails: Set[String] = profiles.filter(_.confirmed).flatMap(_.email).toSet

  /** Return first confirmed email address. */
  //def email: Option[String] = profiles.filter(_.confirmed).flatMap(_.email).headOption

  /** Returns a @username or UUID if username is absent--useful for logging. */
  def usernameId: String = userData.username.fold(id.toString)("@" + _)

  /** Convenience interface to domainAutomarkDeleteCounts0. */
  def domainAutomarkDeleteCounts: Seq[DomainAutomarkDeleteCount] =
    domainAutomarkDeleteCounts0.getOrElse(Seq.empty[DomainAutomarkDeleteCount])
}

object User extends BSONHandlers {

  val VALID_USERNAME: Regex = raw"^([a-zA-Z][a-zA-Z0-9_]+[a-zA-Z_])([0-9]*)$$".r
  val NON_USERNAME_CHARS: String = "[^a-zA-Z0-9_]"

  /** Creates a dummy User without anything but an ID, which is useful to have in some cases. */
  def apply(id: UUID): Option[User] = Some(User(id, UserData(), Nil))

  val ID: String = nameOf[User](_.id)
  val UDATA: String = nameOf[User](_.userData)
  val UNAMELOWx: String = UDATA + "." + nameOf[UserData](_.usernameLower)
  val PROFILES: String = nameOf[User](_.profiles)
  val LINFO: String = nameOf[Profile](_.loginInfo)
  val CONF: String = nameOf[Profile](_.confirmed)
  val PSWNF: String = nameOf[Profile](_.passwordInfo)
  val OA1NF: String = nameOf[Profile](_.oAuth1Info)
  val OA2NF: String = nameOf[Profile](_.oAuth2Info)
  val PLINFOx: String = PROFILES + "." + LINFO
  val PEMAILx: String = PROFILES + "." + nameOf[Profile](_.email)
  val EXCLDOM: String = nameOf[User](_.domainAutomarkDeleteCounts)
  val LIKED: String = nameOf[User](_.fbLiked)
  implicit val extOptsHandler: BSONDocumentHandler[ExtensionOptions] = Macros.handler[ExtensionOptions]
  implicit val userDataHandler: BSONDocumentHandler[UserData] = Macros.handler[UserData]
  implicit val dadcHandler: BSONDocumentHandler[DomainAutomarkDeleteCount] = Macros.handler[DomainAutomarkDeleteCount]
  implicit val userBsonHandler: BSONDocumentHandler[User] = Macros.handler[User]

  implicit val pwordJFmt: OFormat[PasswordInfo] = Json.format[PasswordInfo]
  implicit val oauth1JFmt: OFormat[OAuth1Info] = Json.format[OAuth1Info]
  implicit val oauth2JFmt: OFormat[OAuth2Info] = Json.format[OAuth2Info]
  implicit val profileJFmt: OFormat[Profile] = Json.format[Profile]
  implicit val extOptsJFmt: OFormat[ExtensionOptions] = Json.format[ExtensionOptions]
  implicit val udJFmt: OFormat[UserData] = Json.format[UserData]
  implicit val dadcJFmt: OFormat[DomainAutomarkDeleteCount] = Json.format[DomainAutomarkDeleteCount]
  implicit val userJFmt: OFormat[User] = Json.format[User]
}
