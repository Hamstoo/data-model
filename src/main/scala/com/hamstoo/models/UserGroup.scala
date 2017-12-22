package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ExtendedWriteResult, d, curnt, generateDbId}
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Trait to enable a data structure to be shareable with other users besides the owner user.
  */
trait Shareable {

  /** Shareables must have an ID. */
  def id: String

  /** Shareables must have an owner user (though note that different strings are used for this field in the DB). */
  def userId: UUID

  /** A pair of UserGroups, one for read-only and one for read-write. */
  def sharedWith: Option[SharedWith]

  /** Sorta like "re-tweeets."  However, the number of *current* people with access can be gotten from `sharedWith`. */
  def nSharedFrom: Int

  /** One person can share to multiple, this counts how many individuals have been shared *to*. */
  def nSharedTo: Int

  /** Returns true if the user owns the mark. */
  def ownedBy(user: User): Boolean = user.id == userId

  /**
    * For a user to be authorized, one of the following must be satisfied:
    *   1. User is the owner of this Shareable (i.e. user IDs match)
    *   2. One of the User's email addresses is included in this Shareable's `sharedWith`
    *   3. User's ID is included in this Shareable's `sharedWith`
    */
  def isAuthorizedRead(optUser: Option[User]): Boolean = isAuthorizedWrite(optUser) ||
    optUser.exists(u => // no ownership check necessary here; isAuthorizedWrite already checked
      u.emails.exists(e => sharedWith.exists(_.readOnly.exists(_.isAuthorized(e))))) ||
                           sharedWith.exists(_.readOnly.exists(_.isAuthorized(optUser.map(_.id))))

  def isAuthorizedWrite(optUser: Option[User]): Boolean =
    optUser.exists(u => ownedBy(u) ||
      u.emails.exists(e => sharedWith.exists(_.readWrite.exists(_.isAuthorized(e))))) ||
                           sharedWith.exists(_.readWrite.exists(_.isAuthorized(optUser.map(_.id))))

  /**
    * The owner of a mark may share it with anyone, of course, but non-owners may only re-share (via email--without
    * updating `sharedWith` in the database) if it's owner has previously made it public.
    */
  def isAuthorizedShare(user: User): Boolean = ownedBy(user) || isPublic
  def isPublic: Boolean = sharedWith.exists { sw =>
    Seq(sw.readOnly, sw.readWrite).flatten.exists(ug => UserGroup.PUBLIC_USER_GROUPS.keys.exists(_ == ug.id)) }

  import UserGroup.sharedWithHandler

  /**
    * R sharing level must be at or above RW sharing level.
    * Updating RW permissions with higher than existing R permissions will raise R permissions as well.
    * Updating R permissions with lower than existing RW permissions will reduce RW permissions as well.
    */
  def updateSharedWith(sharedWith: SharedWith, dbColl: () => Future[BSONCollection])
                      (implicit ec: ExecutionContext): Future[Unit] = for {
    c <- dbColl()
    // be sure not to select userId field here as different DB models use different strings (e.g. Annotation.usrId)
    sel = d :~ Shareable.ID -> id :~ curnt
    wr <- c.update(sel, d :~ "$set" -> (d :~ Shareable.SHARED_WITH -> sharedWith))
    _ <- wr.failIfError
  } yield ()
}

object Shareable {
  val ID: String = nameOf[Shareable](_.id)
  val SHARED_WITH: String = nameOf[Shareable](_.sharedWith)
}

/**
  * A pair of UserGroups, one for read-only and one for read-write.
  */
case class SharedWith(readOnly: Option[UserGroup] = None, readWrite: Option[UserGroup] = None)

/**
  * Base trait for groups of users to allow sharing of marks between users.
  *
  * Owners of email addresses in this group will be required to create (and confirm) an account with their
  * "shared with" email address (`emails`) prior to accessing a mark.  If someone tries to access such a
  * mark (e.g. by navigating to the mark's URL) who is not authenticated as an authorized user with one of
  * these email addresses they should be presented with an error message informing them that the mark is
  * available to such a list of users--and that they merely must login as such a user to gain access.
  *
  * @param id          Group ID.
  * @param userIds     Authorized user IDs.  This implementation is incomplete.  It is currently only used for
  *                    public and "logged in" authorization.  Implementation to be completed along with issue #139.
  * @param emails      Authorized email addresses.  Owners of such email addresses will be required to create accounts.
  */
case class UserGroup(id: String = generateDbId(Mark.ID_LENGTH),
                     userIds: Option[Set[UUID]] = None,
                     emails: Option[Set[String]] = None) {

  /** Returns true if the given user is authorized. */
  def isAuthorized(userId: Option[UUID]): Boolean = userId.exists(id => userIds.exists(_.contains(id)))

  /** Returns true if an owner of an email address is authorized--given she has a user account w/ said email. */
  def isAuthorized(email: String): Boolean = emails.exists(_.contains(email))

  protected def isAuthorizedPublic: Boolean = id == UserGroup.PUBLIC_ID
  protected def isAuthorizedLoggedIn: Boolean = id == UserGroup.LOGGED_IN_ID

  /**
    * Greater than (>) indicates that a UserGroup dominates (i.e. is shared with strictly more people than)
    * another UserGroup.
    */
  def >(other: UserGroup): Boolean = {
    import UserGroup.ExtendedOptionSet
    !other.isAuthorizedPublic && (isAuthorizedPublic ||
      !other.isAuthorizedLoggedIn && (isAuthorizedLoggedIn || (userIds > other.userIds && emails > other.emails)))
  }
}

object UserGroup extends BSONHandlers {

  val PUBLIC_ID = "PUBLIC"
  val LOGGED_IN_ID = "LOGGED_IN"

  /**
    * A Shareable is public (by anyone who has the link) if it has this UserGroup in its `sharedWith`.
    *
    * This instance exemplifies why isAuthorized takes an Option; even userId=None will be granted authorization.
    */
  val PUBLIC = new UserGroup(id = PUBLIC_ID) {
    override def isAuthorized(userId: Option[UUID]): Boolean = true
  }

  /**
    * A Shareable is "logged in" authorized (by any *user* who has the link) if it has this UserGroup in its
    * `sharedWith`.
    */
  val LOGGED_IN = new UserGroup(id = LOGGED_IN_ID) {
    override def isAuthorized(userId: Option[UUID]): Boolean = userId.isDefined
  }

  /** Enumerated, special, public user groups used by MongoUserDao. */
  val PUBLIC_USER_GROUPS = Map(PUBLIC.id -> PUBLIC, LOGGED_IN.id -> LOGGED_IN)

  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val userGroupHandler: BSONDocumentHandler[UserGroup] = Macros.handler[UserGroup]

  /** Used for `emails > other.emails` above and `union` and `intersection` below. */
  implicit class ExtendedOptionSet[T](private val self: Option[Set[T]]) extends AnyVal {

    def >(other: Option[Set[T]]): Boolean = other == other.intersect(self)

    def union(other: Option[Set[T]]): Option[Set[T]] =
      self.fold(other)(s => other.fold(self)(o => Some(s.union(o))))

    def intersect(other: Option[Set[T]]): Option[Set[T]] =
      self.fold(Option.empty[Set[T]])(s => other.fold(Option.empty[Set[T]])(o => Some(s.intersect(o))))
  }

  // inline testing (I wonder if there's a doctest-like module for Scala)
  assert {
    val a = Some(Set(1, 2, 3))
    val b = Some(Set(1, 2))
    val c = Some(Set.empty[Int])
    val d = None
    (a > b && b > c && c > d) && !(d > c || c > b || b > a || a > a || b > b || c > c || d > d)
  }

  /** There should be a generic for something like this--maybe there is. */
  implicit class ExtendedOptionUserGroup(private val self: Option[UserGroup]) extends AnyVal {

    def union(other: Option[UserGroup]): Option[UserGroup] = self.fold(other) { sg =>
      other.fold(self) { og =>
        if (sg > og) self
        else if (og > sg) other
        else Some(UserGroup(userIds = sg.userIds.union(og.userIds), emails = sg.emails.union(og.emails)))
      }
    }

    def intersect(other: Option[UserGroup]): Option[UserGroup] = self.fold(Option.empty[UserGroup]) { sg =>
      other.fold(Option.empty[UserGroup]) { og =>
        if (sg > og) other
        else if (og > sg) self
        else Some(UserGroup(userIds = sg.userIds.intersect(og.userIds), emails = sg.emails.intersect(og.emails)))
      }
    }

    def -(other: Option[UserGroup]): Option[UserGroup] = self.fold(Option.empty[UserGroup]) { sg =>
      other.fold(self) { og =>

      }
    }
  }
}



