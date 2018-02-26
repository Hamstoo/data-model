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
  def isAuthorizedDelete(user: Option[User]): Boolean = user.exists(this.ownedBy)

  /**
    * For a user to be authorized, one of the following must be satisfied:
    *   1. User is the owner of this Shareable (i.e. user IDs match)
    *   2. One of the User's email addresses is included in this Shareable's `sharedWith`
    *   3. User's ID is included in this Shareable's `sharedWith`
    */
  def isAuthorizedRead(user: Option[User])
                      (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] = for {
    aw <- isAuthorizedWrite(user) // no ownership check necessary here; isAuthorizedWrite will check
    ar <- if (aw) Future.successful(true) else { sharedWith match {
      case Some(SharedWith(Some(sgRO), _, _)) => sgRO.isAuthorized(user)
      case _ => Future.successful(false)
    }}
  } yield ar

  def isAuthorizedWrite(user: Option[User])
                       (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] = for {
    ob <- Future.successful(user.exists(ownedBy))
    aw <- if (ob) Future.successful(true) else { sharedWith match {
      case Some(SharedWith(_, Some(sgRW), _)) => sgRW.isAuthorized(user)
      case _ => Future.successful(false)
    }}
  } yield aw

  /**
    * The owner of a mark may share it with anyone, of course, but non-owners may only re-share (via email--without
    * updating `sharedWith` in the database, of course) if it's owner has previously made it public.
    */
  def isAuthorizedShare(user: Option[User]): Boolean = user.exists(ownedBy(_) || isPublic)
  def isPublic: Boolean = sharedWith.exists { sw =>
    import SharedWith.{PUBLIC_LEVELS, Level}
    Seq(sw.readOnly, sw.readWrite).flatten.exists(sg => PUBLIC_LEVELS.contains(Level(sg.level)))
  }
}

object Shareable {
  val ID: String = nameOf[Shareable](_.id)
  val USR: String = nameOf[Shareable](_.userId)
  val SHARED_WITH: String = nameOf[Shareable](_.sharedWith)
  val READONLYx: String = SHARED_WITH + "." + nameOf[SharedWith](_.readOnly)
  val READWRITEx: String = SHARED_WITH + "." + nameOf[SharedWith](_.readWrite)
  val N_SHARED_FROM: String = nameOf[Shareable](_.nSharedFrom)
  val N_SHARED_TO: String = nameOf[Shareable](_.nSharedTo)
  val READONLYxLEVEL: String = READONLYx + "." +  nameOf[ShareGroup](_.level)
}

/**
  * A pair of UserGroups, one for read-only and one for read-write.
  */
case class SharedWith(readOnly: Option[ShareGroup] = None,
                      readWrite: Option[ShareGroup] = None,
                      ts: TimeStamp = TIME_NOW) {

  /**
    * Returns the union of all of the email addresses from either UserGroup, both those that are found in
    * the profiles of the groups' userIds and those in the groups' emails.
    */
  def emails(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Set[String]] = {
    val futs = Seq(readOnly, readWrite).map { sg => UserGroup.retrieve(sg.flatMap(_.group)) }
    for (ro <- futs.head; rw <- futs(1); emails <- SharedWith.emails(ro, rw)) yield emails
  }
}

object SharedWith {

  /** Enumeration of sharing levels. */
  object Level extends Enumeration {

    // Only the owner has access.  This is the default.
    val PRIVATE: Value = Value(0)

    // Only listed users have access.  Requires login first, of course, to authenticate user.
    val LISTED: Value = Value(1)

    // A Shareable is "logged in" authorized (by any *user* who has the link) if it has this UserGroup in its
    // `sharedWith`.
    val LOGGED_IN: Value = Value(2)

    // A Shareable is public (by anyone who has the link) if it has this UserGroup in its `sharedWith`.
    // This instance exemplifies why isAuthorized takes an Option; even userId=None will be granted authorization.
    val PUBLIC: Value = Value(3)

    // https://stackoverflow.com/questions/24851677/reactivemongo-how-to-write-macros-handler-to-enumeration-object
//    implicit val enumReads: Reads[Level] = EnumUtils.enumReads(Level)
//    implicit def enumWrites: Writes[Level] = EnumUtils.enumWrites
//    implicit object BSONEnumHandler extends BSONHandler[BSONString, Level] {
//      def read(doc: BSONInteger) = Level.Value(doc.value)
//      //def read(doc: BSONString) = Level.withName(doc.value)
//      def write(stats: Level) = BSON.write(stats.toString)
//    }
  }

  /** "Public" (as in: not based on a specific "listed" set of users) authorization levels. */
  val PUBLIC_LEVELS: Map[Level.Value, Option[User] => Boolean] = Map(Level.PUBLIC -> ((_: Option[User]) => true),
                                                                  Level.LOGGED_IN -> ((u: Option[User]) => u.isDefined))

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
  * A Level-UserGroup pair.  The former is used for authentication purposes.  The latter is used for
  * both authentication and email notification.
  * @param level  If the LISTED then the set of users/emails in the UserGroup is used for authentication but
  *               otherwise it is only used for notification.
  * @param group  This can be None if `level` is not LISTED.
  */
case class ShareGroup(level: Int, group: Option[ObjectId]) {

  import SharedWith.{PUBLIC_LEVELS, Level}

  /** Returns true if the given user is authorized, either via (optional) user ID or email. */
  def isAuthorized(user: Option[User])(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] =
    PUBLIC_LEVELS.get(Level(level)).fold {
      // defer to the "listed" users in the group
      UserGroup.retrieve(group).flatMap(_.fold(Future.successful(false))(_.isAuthorized(user)))
    }(isAuthFunc => Future.successful(isAuthFunc(user)))

  /**
    * Greater than (>) indicates that a ShareGroup dominates (i.e. is shared with strictly more people than)
    * another ShareGroup.
    */
  def >(other: ShareGroup)(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] =
    if (level > other.level) Future.successful(true)
    else if (Level(level) == Level.LISTED && Level(other.level) == Level.LISTED) {
      val futs = Seq(group, other.group).map(UserGroup.retrieve) // calling these `retrieve`s inside a for-
      for (ugT <- futs.head; ugO <- futs(1)) yield {             // expression would cause them to run sequentially
        import UserGroup.ExtendedOptionSet
        ugT.flatMap(_.userIds) > ugO.flatMap(_.userIds) && ugT.flatMap(_.emails) > ugO.flatMap(_.emails)
      }
    } else Future.successful(false)

  def >=(other: ShareGroup)(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] =
    if (this == other) Future.successful(true) else this > other
}

object ShareGroup {

  val GROUP: String = nameOf[ShareGroup](_.group)
  val LEVEL: String = nameOf[ShareGroup](_.level)

  /** Used by MongoMarksDao.updateSharedWith. */
  def xapply(level: SharedWith.Level.Value, ug: Option[UserGroup]): Option[ShareGroup] =
    if (level == SharedWith.Level.PRIVATE) None else Some(ShareGroup(level.id, ug.map(_.id)))
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
  def isAuthorized(user: Option[User])(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Boolean] = {

    // isAuthorizedUserId(user.map(_.id)) || user.exists(_.emails.exists(isAuthorizedEmail))
    if (isAuthorizedUserId(user.map(_.id))) Future.successful(true) else {

      // the User instance may have been constructed with User.apply from merely a UUID, in which case we need
      // to get its emails from the db (this allows callers with a UUID to not have to perform this lookup themselves)
      user match {
        case None => Future.successful(false)
        case Some(u) =>
          if (u.emails.nonEmpty) Future.successful(u.emails.exists(isAuthorizedEmail))
          else userDao.retrieve(u.id).map(_.fold(false)(_.emails.exists(isAuthorizedEmail)))
      }
    }
  }
}

object UserGroup extends BSONHandlers {

  /** Special hashing function for UserGroups to use as their MongoDB index key. */
  private final class Hashing extends hashing.Hashing[UserGroup] {
    override def hash(x: UserGroup): Int = x.copy(id = "", sharedObjs = Seq.empty[UserGroup.SharedObj]).##
  }

  /** Wrapper function (unsure why there's any need for the Hashing class above). */
  def hash(x: UserGroup): Int = new Hashing().hash(x)

  /** Object ID (e.g. mark or highlight ID) paired with a time stamp indicating when that ID was shared. */
  case class SharedObj(id: ObjectId, ts: TimeStamp)

  /** Query the database for a UserGroup given its ID. */
  def retrieve(opt: Option[ObjectId])(implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Option[UserGroup]] =
    opt.fold(Future.successful(Option.empty[UserGroup]))(userDao.retrieveGroup)

  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val userGroupHandler: BSONDocumentHandler[UserGroup] = Macros.handler[UserGroup]
  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedObjHandler: BSONDocumentHandler[SharedObj] = Macros.handler[SharedObj]

  val HASH: String = nameOf[UserGroup](_.hash)
  val SHROBJS: String = nameOf[UserGroup](_.sharedObjs)
  val SHROBJSID: String =  SHROBJS + "." + nameOf[UserGroup.SharedObj](_.id)
  val EMAILS: String = nameOf[UserGroup](_.emails)

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
}



