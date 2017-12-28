package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.daos.MongoUserDao
import com.hamstoo.utils.{ObjectId, TIME_NOW, TimeStamp, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.hashing

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
  def nSharedFrom: Option[Int]

  /** One person can share to multiple, this counts how many individuals have been shared *to*. */
  def nSharedTo: Option[Int]

  /** Returns true if the user owns the mark. */
  def ownedBy(user: User): Boolean = user.id == userId

  /**
    * For a user to be authorized, one of the following must be satisfied:
    *   1. User is the owner of this Shareable (i.e. user IDs match)
    *   2. One of the User's email addresses is included in this Shareable's `sharedWith`
    *   3. User's ID is included in this Shareable's `sharedWith`
    */
  def isAuthorizedRead(optUser: Option[User])
                      (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] = for {
    aw <- isAuthorizedWrite(optUser) // no ownership check necessary here; isAuthorizedWrite will check
    ar <- if (aw) Future.successful(true) else { sharedWith match {
      case Some(SharedWith(Some(roId), _, _)) => userDao.retrieveGroup(roId).map(_.exists(_.isAuthorized(optUser)))
      case _ => Future.successful(false)
    }}
  } yield ar

  def isAuthorizedWrite(optUser: Option[User])
                       (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] = for {
    ob <- Future.successful(optUser.exists(ownedBy))
    aw <- if (ob) Future.successful(true) else { sharedWith match {
      case Some(SharedWith(_, Some(rwId), _)) => userDao.retrieveGroup(rwId).map(_.exists(_.isAuthorized(optUser)))
      case _ => Future.successful(false)
    }}
  } yield aw

  /**
    * The owner of a mark may share it with anyone, of course, but non-owners may only re-share (via email--without
    * updating `sharedWith` in the database, of course) if it's owner has previously made it public.
    */
  def isAuthorizedShare(user: User): Boolean = ownedBy(user) || isPublic
  def isPublic: Boolean = sharedWith.exists { sw =>
    Seq(sw.readOnly, sw.readWrite).flatten.exists(id => UserGroup.PUBLIC_USER_GROUPS.keys.exists(_ == id)) }
}

object Shareable {
  val ID: String = nameOf[Shareable](_.id)
  val SHARED_WITH: String = nameOf[Shareable](_.sharedWith)
  val N_SHARED_FROM: String = nameOf[Shareable](_.nSharedFrom)
  val N_SHARED_TO: String = nameOf[Shareable](_.nSharedTo)
}

/**
  * A pair of UserGroups, one for read-only and one for read-write.
  */
case class SharedWith(readOnly: Option[ObjectId] = None,
                      readWrite: Option[ObjectId] = None,
                      ts: TimeStamp = TIME_NOW) {

  /**
    * Returns the union of all of the email addresses from either UserGroup, both those that are found in
    * the profiles of the groups' userIds and those in the groups' emails.
    */
  def emails(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Set[String]] = for {
    ro <- if (readOnly.isEmpty) Future.successful(None) else userDao.retrieveGroup(readOnly.get)
    rw <- if (readWrite.isEmpty) Future.successful(None) else userDao.retrieveGroup(readWrite.get)
    emails <- SharedWith.emails(ro, rw)
  } yield emails
}

object SharedWith {

  /**
    * Returns the union of all of the email addresses from either UserGroup, both those that are found in
    * the profiles of the groups' userIds and those in the groups' emails.
    */
  def emails(readOnly: Option[UserGroup], readWrite: Option[UserGroup])
            (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Set[String]] = {
    import UserGroup.ExtendedOptionSet

    val userIds = (readOnly.flatMap(_.userIds) union readWrite.flatMap(_.userIds)).getOrElse(Set.empty[UUID])
    val rawEmails = (readOnly.flatMap(_.emails) union readWrite.flatMap(_.emails)).getOrElse(Set.empty[String])

    // convert userIds into a set of email addresses (which could be empty)
    val futUserEmails: Future[Set[String]] = for {
      optUsers <- Future.sequence(userIds.map(userDao.retrieve))
    } yield optUsers.flatten.flatMap(_.profiles.flatMap(_.email))

    futUserEmails.map(_.union(rawEmails))
  }
}

/**
  * Base trait for groups of users to allow sharing of marks between users.
  *
  * Owners of email addresses in this group will be required to create (and confirm) an account with their
  * "shared with" email address (`emails`) prior to accessing a mark.  If someone tries to access such a
  * mark (e.g. by navigating to the mark's URL) who is not authenticated as an authorized user with one of
  * these email addresses they should be presented with an error message informing them that the mark is
  * available to such a list of users--and that they merely must login as such a user to gain access.
  *
  * All of the fields in this class must be Options because they are all optional when performing JSON
  * validation as in `MarksController.share`.
  *
  * @param id          Group ID.
  * @param userIds     Authorized user IDs.  This implementation is incomplete.  It is currently only used for
  *                    public and "logged in" authorization.  Implementation to be completed along with issue #139.
  * @param emails      Authorized email addresses.  Owners of such email addresses will be required to create accounts.
  * @param sharedObjs  Object IDs (e.g. mark or highlight IDs) that have been shared with this UserGroup in the past.
  * @param hash        A hash of this UserGroup to use as a MongoDB index key.
  */
case class UserGroup(id: ObjectId = generateDbId(Mark.ID_LENGTH),
                     userIds: Option[Set[UUID]] = None,
                     emails: Option[Set[String]] = None,
                     sharedObjs: Seq[UserGroup.SharedObj] = Seq.empty[UserGroup.SharedObj],
                     var hash: Int = 0) {

  // if `id` is None then let `hash` be None also
  hash = if (id.isEmpty) 0 else UserGroup.hash(this)

  /** Returns true if the given (optional) user ID is authorized. */
  protected def isAuthorizedUserId(userId: Option[UUID]): Boolean = userId.exists(u => userIds.exists(_.contains(u)))

  /** Returns true if an owner of an email address is authorized--given she has a user account w/ said email. */
  protected def isAuthorizedEmail(email: String): Boolean = emails.exists(_.contains(email))

  /** Returns true if the given user is authorized, either via (optional) user ID or email. */
  def isAuthorized(user: Option[User]): Boolean =
    isAuthorizedUserId(user.map(_.id)) || user.exists(_.emails.exists(isAuthorizedEmail))

  protected def isAuthorizedPublic: Boolean = id.contains(UserGroup.PUBLIC_ID)
  protected def isAuthorizedLoggedIn: Boolean = id.contains(UserGroup.LOGGED_IN_ID)

  /**
    * Greater than (>) indicates that a UserGroup dominates (i.e. is shared with strictly more people than)
    * another UserGroup.
    */
  def >(other: UserGroup): Boolean = {
    import UserGroup.ExtendedOptionSet
    !other.isAuthorizedPublic && (isAuthorizedPublic ||
      !other.isAuthorizedLoggedIn && (isAuthorizedLoggedIn || (userIds > other.userIds && emails > other.emails)))
  }

  def >=(other: UserGroup): Boolean = this == other || this > other
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
    override def isAuthorized(user: Option[User]): Boolean = true
  }

  /**
    * A Shareable is "logged in" authorized (by any *user* who has the link) if it has this UserGroup in its
    * `sharedWith`.
    */
  val LOGGED_IN = new UserGroup(id = LOGGED_IN_ID) {
    override def isAuthorized(user: Option[User]): Boolean = user.isDefined
  }

  /** Enumerated, special, public user groups used by MongoUserDao. */
  val PUBLIC_USER_GROUPS: Map[ObjectId, UserGroup] = Map(PUBLIC_ID -> PUBLIC, LOGGED_IN_ID -> LOGGED_IN)

  /** Special hashing function for UserGroups to use as their MongoDB index key. */
  private final class Hashing extends hashing.Hashing[UserGroup] {
    override def hash(x: UserGroup): Int = x.copy(id = "", sharedObjs = Seq.empty[UserGroup.SharedObj]).##
  }

  /** Wrapper function (unsure why there's any need for the Hashing class above). */
  def hash(x: UserGroup): Int = new Hashing().hash(x)

  /** Object ID (e.g. mark or highlight ID) paired with a time stamp indicating when that ID was shared. */
  case class SharedObj(id: ObjectId, ts: TimeStamp)

  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val userGroupHandler: BSONDocumentHandler[UserGroup] = Macros.handler[UserGroup]
  implicit val sharedObjHandler: BSONDocumentHandler[SharedObj] = Macros.handler[SharedObj]

  val HASH: String = nameOf[UserGroup](_.hash)
  val SHROBJS: String = nameOf[UserGroup](_.sharedObjs)

  /** Used for `emails > other.emails` above and `union` and `intersection` below. */
  implicit class ExtendedOptionSet[T](private val self: Option[Set[T]]) extends AnyVal {

    protected def none = Option.empty[Set[T]]

    def >(other: Option[Set[T]]): Boolean = other != self && other == other.intersect(self)

    def union(other: Option[Set[T]]): Option[Set[T]] =
      self.fold(other)(s => other.fold(self)(o => Some(s.union(o))))

    def intersect(other: Option[Set[T]]): Option[Set[T]] =
      self.fold(none)(s => other.fold(none)(o => Some(s.intersect(o))))

    def -(other: Option[Set[T]]): Option[Set[T]] =
      self.fold(none)(s => if (s.isEmpty) none else Some(other.fold(s)(s.diff)))
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

    def -(other: Option[UserGroup]): Option[UserGroup] = self flatMap { sg =>
      other match {
        case None => Some(sg)
        case Some(og) if og >= sg => None
        case Some(_) if sg.isAuthorizedPublic || sg.isAuthorizedLoggedIn => Some(sg)
        case Some(og) => Some(UserGroup(userIds = sg.userIds - og.userIds, emails = sg.emails - og.emails))
      }
    }
  }
}



