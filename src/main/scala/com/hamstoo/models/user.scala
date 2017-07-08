package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.fieldName
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.{OAuth1Info, OAuth2Info}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

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

case class ExtensionOptions(autoSync: Option[Boolean] = None,
                            menuIntegration: Option[Boolean] = None,
                            minutesActive: Option[Int] = None)

case class UserData(
                     firstName: Option[String] = None,
                     lastName: Option[String] = None,
                     avatar: Option[String] = None,
                     extOpts: Option[ExtensionOptions] = None,
                     tutorial: Option[Boolean] = Some(true))

case class User(id: UUID, userData: UserData, profiles: List[Profile]) extends Identity {

  def defaults(): User = {
    val ps = profiles filter (_.confirmed)
    this copy (userData = UserData(
      this.userData.firstName orElse (ps collectFirst { case Profile(_, _, _, Some(s), _, _, _, _, _, _) => s }),
      this.userData.lastName orElse (ps collectFirst { case Profile(_, _, _, _, Some(s), _, _, _, _, _) => s }),
      this.userData.avatar orElse (ps collectFirst { case Profile(_, _, _, _, _, Some(s), _, _, _, _) => s })))
  }

  def profileFor(loginInfo: LoginInfo): Option[Profile] = profiles find (_.loginInfo == loginInfo)
}

object User extends BSONHandlers {
  val ID: String = fieldName[User]("id")
  val LGNF: String = fieldName[User]("loginInfo")
  val PROF: String = fieldName[User]("profiles")
  val PLGNF: String = s"$PROF.$LGNF"
  val CONF: String = fieldName[User]("confirmed")
  val PSWNF: String = fieldName[User]("passwordInfo")
  val OA1NF: String = fieldName[User]("oAuth1Info")
  val OA2NF: String = fieldName[User]("oAuth2Info")
  val EMAIL: String = fieldName[User]("email")
  implicit val extOptsHandler: BSONDocumentHandler[ExtensionOptions] = Macros.handler[ExtensionOptions]
  implicit val userDataHandler: BSONDocumentHandler[UserData] = Macros.handler[UserData]
  implicit val userBsonHandler: BSONDocumentHandler[User] = Macros.handler[User]
}
