package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

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
  * @param userName   User handle or username (issue #139)
  * @param extOpts    Extension options.
  * @param tutorial   If true, the user will see the tutorial on next login.
  */
case class UserData(
                     firstName: Option[String] = None,
                     lastName: Option[String] = None,
                     userName: Option[String] = None,
                     avatar: Option[String] = None,
                     extOpts: Option[ExtensionOptions] = None,
                     tutorial: Option[Boolean] = Some(true))

/**
  * Finally, the full User object that is stored in the database for each user.  Notice that this class
  * extends Silhouette's Identity trait.
  * @param id        Unique ID.
  * @param userData  A single base UserData object.
  * @param profiles  A list of linked social Profiles.
  */
case class User(id: UUID, userData: UserData, profiles: List[Profile]) extends Identity {

  // TODO: where is this method used, if anywhere?  it's not overriding anything
  /*override*/ def defaults(): User = {
    val ps = profiles filter (_.confirmed)
    this copy (userData = UserData(
      this.userData.firstName orElse (ps collectFirst { case Profile(_, _, _, Some(s), _, _, _, _, _, _) => s }),
      this.userData.lastName orElse (ps collectFirst { case Profile(_, _, _, _, Some(s), _, _, _, _, _) => s }),
      this.userData.avatar orElse (ps collectFirst { case Profile(_, _, _, _, _, Some(s), _, _, _, _) => s })))
  }

  /** Returns the Profile corresponding to the given LoginInfo. */
  def profileFor(loginInfo: LoginInfo): Option[Profile] = profiles.find(_.loginInfo == loginInfo)

  /** Returns true if the email-address/Profile for the given LoginInfo has been confirmed. */
  def confirmed(loginInfo: LoginInfo): Boolean = profileFor(loginInfo).exists(_.confirmed)

  /** Returns a list of all of the User's *confirmed* email addresses on file. */
  def emails: Set[String] = profiles.filter(_.confirmed).flatMap(_.email).toSet
}

object User extends BSONHandlers {

  /** Creates a dummy User without anything but an ID, which is useful to have in some cases. */
  def apply(id: UUID): User = User(id, UserData(), Nil)

  val ID: String = nameOf[User](_.id)
  val LGNF: String = nameOf[Profile](_.loginInfo)
  val PROF: String = nameOf[User](_.profiles)
  val PLGNF: String = s"$PROF.$LGNF"
  val CONF: String = nameOf[Profile](_.confirmed)
  val PSWNF: String = nameOf[Profile](_.passwordInfo)
  val OA1NF: String = nameOf[Profile](_.oAuth1Info)
  val OA2NF: String = nameOf[Profile](_.oAuth2Info)
  val EMAIL: String = nameOf[Profile](_.email)
  implicit val extOptsHandler: BSONDocumentHandler[ExtensionOptions] = Macros.handler[ExtensionOptions]
  implicit val userDataHandler: BSONDocumentHandler[UserData] = Macros.handler[UserData]
  implicit val userBsonHandler: BSONDocumentHandler[User] = Macros.handler[User]
}
