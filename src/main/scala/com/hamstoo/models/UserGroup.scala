package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.generateDbId
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Trait to enable a data structure to be shareable with other users besides the owner user.
  */
trait Shareable {

  def userId: UUID

  /** This is a *Set* of UserGroups mainly so that some users can have read perms and others write. */
  def sharedWith: Option[Set[UserGroup]]

  /** See UserGroup ScalaDoc */
  def isAuthorizedRead(optUserId: Option[UUID]): Boolean =
    optUserId.contains(userId) || sharedWith.exists(_.exists(_.isAuthorizedRead(optUserId)))
  def isAuthorizedWrite(optUserId: Option[UUID]): Boolean =
    optUserId.contains(userId) || sharedWith.exists(_.exists(_.isAuthorizedWrite(optUserId)))
  def isAuthorizedRead(emailAddr: String): Boolean = sharedWith.exists(_.exists(_.isAuthorizedRead(emailAddr)))
  def isAuthorizedWrite(emailAddr: String): Boolean = sharedWith.exists(_.exists(_.isAuthorizedWrite(emailAddr)))
}

/**
  * Base trait for groups of users to allow sharing of marks between users.
  *
  * Owners of email addresses in this group will be required to create (and confirm) an account with their
  * "shared with" email address (`emailAddrs`) prior to accessing a mark.  If someone tries to access such a
  * mark (e.g. by navigating to the mark's URL) who is not authenticated as an authorized user with one of
  * these email addresses they should be presented with an error message informing them that the mark is
  * available to such a list of users--and that they merely must login as such a user to gain access.
  *
  * When an email address is authorized, we can add the respective UUID to the group's set of UUIDs.  We
  * should probably leave the respective email address intact however in case the user deletes and re-creates
  * their account.
  *
  * @param id          Group ID.
  * @param readOnly    If true, then authorization is read only; if false, then authorization is read/write.
  * @param userIds     Authorized user IDs.
  * @param emailAddrs  Authorized email addresses.  Owners of such email addresses will be required to create accounts.
  */
case class UserGroup(id: String = generateDbId(Mark.ID_LENGTH),
                     readOnly: Boolean = true,
                     userIds: Option[Set[UUID]] = None,
                     emailAddrs: Option[Set[String]] = None) {

  /** Returns true if the given user is authorized to read. */
  def isAuthorizedRead(userId: Option[UUID]): Boolean = userId.exists(id => userIds.exists(_.contains(id)))

  /** Returns true if the given user is authorized to write. */
  def isAuthorizedWrite(userId: Option[UUID]): Boolean = !readOnly && isAuthorizedRead(userId)

  /** Returns true if an owner of an email address is authorized to read--after creating a user account. */
  def isAuthorizedRead(emailAddr: String): Boolean = emailAddrs.exists(_.contains(emailAddr))

  /** Returns true if an owner of an email address is authorized to write--after creating a user account. */
  def isAuthorizedWrite(emailAddr: String): Boolean = !readOnly && isAuthorizedRead(emailAddr)
}

object UserGroup extends BSONHandlers {

  /**
    * A Shareable is publicly readable (by anyone who has the link) if it has this UserGroup in its `sharedWith` set.
    *
    * This instance exemplifies why isAuthorizedRead takes an Option; even userId=None will be granted authorization.
    */
  val PUBLIC_READ = new UserGroup(id = "PUBLIC_READ") {
    override def isAuthorizedRead(userId: Option[UUID]): Boolean = true
  }

  /**
    * A Shareable is publicly writable (by anyone who has the link) if it has this UserGroup in its `sharedWith` set.
    */
  val PUBLIC_RW = new UserGroup(id = "PUBLIC_RW", readOnly = false) {
    override def isAuthorizedRead(userId: Option[UUID]): Boolean = true
  }

  /**
    * A Shareable is "user aware" readable (by any *user* who has the link) if it has this UserGroup in its
    * `sharedWith` set.
    *
    * See also: https://www.silhouette.rocks/v4.0/docs/endpoints
    */
  val USER_AWARE_READ = new UserGroup(id = "USER_AWARE_READ") {
    override def isAuthorizedRead(userId: Option[UUID]): Boolean = userId.isDefined
  }

  /**
    * A Shareable is "user aware" writable (by any *user* who has the link) if it has this UserGroup in its
    * `sharedWith` set.
    */
  val USER_AWARE_RW = new UserGroup(id = "USER_AWARE_RW", readOnly = false) {
    override def isAuthorizedRead(userId: Option[UUID]): Boolean = userId.isDefined
  }

  /** Enumerated, special user groups used by MongoUserDao. */
  val SPECIAL_USER_GROUPS = Map(PUBLIC_READ.id -> PUBLIC_READ,
                                PUBLIC_RW.id -> PUBLIC_RW,
                                USER_AWARE_READ.id -> USER_AWARE_READ,
                                USER_AWARE_RW.id -> USER_AWARE_RW)

  implicit val userGroupHandler: BSONDocumentHandler[UserGroup] = Macros.handler[UserGroup]
}



