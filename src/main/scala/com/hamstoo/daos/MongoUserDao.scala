package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.SharedWith.Level
import com.hamstoo.models._
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONArray, BSONDocument, BSONRegex, BSONString}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object MongoUserDao {
  var migrateData: Boolean = scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)
}

/**
  * Data access object for user accounts.
  */
class MongoUserDao(db: () => Future[DefaultDB]) extends IdentityService[User] {

  val logger: Logger = Logger(classOf[MongoUserDao])
  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.models.ShareGroup.{GROUP, LEVEL}
  import com.hamstoo.models.Shareable.{READONLYx, READWRITEx, SHARED_WITH, USR}
  import com.hamstoo.models.User._
  import com.hamstoo.models.UserGroup.{EMAILS, HASH, SHROBJS, SHROBJSID, sharedObjHandler, sharedWithHandler, userGroupHandler}
  import com.hamstoo.utils._

  // intermediate aggregated collection names
  val usersFoundCollName = "usersFound"
  val entriesFoundCollName = "entriesFound"

  // data field names used during aggregation
  val usersFoundCollId: String = "$" + usersFoundCollName + "." + User.ID
  val usersFoundCollUserName: String = "$" + usersFoundCollName + "." + UNAMEx
  val entriesFoundCollUserId: String = entriesFoundCollName + "." + USR
  val usersFoundCollUserNameLower: String = "$" + usersFoundCollName + "." + UNAMELOWx

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private def dbColl(): Future[BSONCollection] = db().map(_ collection "users")
  private def groupColl(): Future[BSONCollection] = db().map(_ collection "usergroups")
  private def marksColl(): Future[BSONCollection] = db().map(_ collection "entries")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(PLINFOx -> Ascending :: Nil, unique = true) % s"bin-$PLINFOx-1-uniq" ::
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(PEMAILx -> Ascending :: Nil) % s"bin-$PEMAILx-1" ::
    Index(UNAMELOWx -> Ascending :: Nil, unique = true) % s"bin-$UNAMELOWx-1-uniq" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 323 seconds)

  private val groupIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(HASH -> Ascending :: Nil) % s"bin-$HASH-1" :: // MongoDB Hashed Indexes don't seem to work for this purpose
    Nil toMap;
  Await.result(groupColl().map(_.indexesManager.ensure(groupIndxs)), 223 seconds)

  /** Saves or updates user account data by matching provided `User`'s `.id`. */
  def save(u: User): Future[Unit] = for {
    c <- dbColl()
    wr <- c.update(d :~ ID -> u.id.toString, u, upsert = true)
    _ <- wr.failIfError
  } yield ()

  /** Start with a username, but then return a different one if that one is already taken. */
  def nextUsername(startWith: String): Future[String] = {
    retrieveByUsername(startWith).flatMap { _.fold(Future.successful(startWith)) { _ =>
      val User.VALID_USERNAME(alpha, numeric) = startWith
      val number = if (numeric.isEmpty) 2 else numeric.toInt + 1
      nextUsername(alpha + number)
    }}
  }

  /** Retrieves user account data by login. */
  def retrieve(loginInfo: LoginInfo): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ PLINFOx -> loginInfo).one[User])

  /** Retrieves user account data by user id. */
  def retrieve(userId: UUID): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ ID -> userId.toString).one[User])

  /** Retrieves user account data by email. */
  def retrieve(email: String): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ PEMAILx -> email).one[User])

  /** Retrieves user account data by username. */
  def retrieveByUsername(username: String): Future[Option[User]] =
    dbColl().flatMap(_.find(d :~ UNAMELOWx -> username.toLowerCase).one[User])

  /** Attaches provided `Profile` to user account by user id. */
  def link(userId: UUID, profile: Profile): Future[User] = for {
    c <- dbColl()
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, d :~ "$push" -> (d :~ PROFILES -> profile), fetchNewObject = true)
  } yield wr.result[User].get

  /** Detaches provided login from user account by id. */
  def unlink(userId: UUID, loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$pull" -> (d :~ PROFILES -> (d :~ LINFO -> loginInfo))
    wr <- c.findAndUpdate(d :~ ID -> userId.toString, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Updates one of user account's profiles by login. */
  def update(profile: Profile): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROFILES.$$" -> profile)
    wr <- c.findAndUpdate(d :~ PLINFOx -> profile.loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Sets one of user account profiles to 'confirmed' by login. */
  def confirm(loginInfo: LoginInfo): Future[User] = for {
    c <- dbColl()
    upd = d :~ "$set" -> (d :~ s"$PROFILES.$$.$CONF" -> true)
    wr <- c.findAndUpdate(d :~ PLINFOx -> loginInfo, upd, fetchNewObject = true)
  } yield wr.result[User].get

  /** Removes user account by id. */
  def delete(userId: UUID): Future[Unit] = for {
    c <- dbColl()
    wr <- c.remove(d :~ ID -> userId.toString)
    _ <- wr.failIfError
  } yield ()

  /**
    * Saves or updates user group with the given ID.  Optionally provide an ObjectId-TimeStamp pair to add
    * to the saved document's `sharedObjs` list.
    */
  def saveGroup(ug: UserGroup, sharedObj: Option[UserGroup.SharedObj] = None): Future[UserGroup] = for {
    c <- groupColl()
    existing <- retrieveGroup(ug)

    // be sure to insert, not upsert, here b/c we want to avoid overwriting any existing sharedObjs history
    wr <- if (existing.isEmpty) c.insert(ug) else
      c.update(d :~ ID -> existing.get.id, d :~ "$push" -> (d :~ SHROBJS -> (d :~ "$each" -> ug.sharedObjs)))
    _ <- wr.failIfError

    // optionally update the UserGroup's list of objects it was used to share
    _ <- if (sharedObj.isEmpty) Future.successful {} else
      c.update(d :~ ID -> existing.fold(ug.id)(_.id), d :~ "$push" -> (d :~ SHROBJS -> sharedObj.get))

  } yield existing.getOrElse(ug)

  /** Retrieves user group either from PUBLIC_USER_GROUPS or, if not there, from the database. */
  def retrieveGroup(ugId: ObjectId): Future[Option[UserGroup]] = for {
    c <- groupColl()
    _ = logger.debug(s"Retrieving user group $ugId")
    opt <- c.find(d :~ ID -> ugId).one[UserGroup]
  } yield {
    if (opt.isDefined) logger.debug(s"Found user group $ugId")
    opt
  }

  /**
    * Retrieves a user group from the database first based on its hash, then on its hashed fields. This allows
    * for the prevention of UserGroup duplicates.
    */
  protected def retrieveGroup(ug: UserGroup): Future[Option[UserGroup]] = for {
    c <- groupColl()
    found <- c.find(d :~ HASH -> UserGroup.hash(ug)).coll[UserGroup, Seq]()
  } yield found.find(x => x.userIds == ug.userIds && x.emails == ug.emails)

  /** Retrieve a list of usernames and email addresses that the given user has shared with, in that order. */
  def retrieveRecentSharees(userId: UUID): Future[Seq[String]] = for {

    // first fetch the SharedWiths from the given user's (current) marks
    cMarks <- marksColl()
    sel = d :~ Mark.USR -> userId :~ curnt :~ SHARED_WITH -> (d :~ "$exists" -> 1)
    prj = d :~ Shareable.SHARED_WITH -> 1 :~ "_id" -> 0
    sharedWiths <- cMarks.find(sel, prj).coll[BSONDocument, Seq]()

    // traverse down through the data model hierarchy to get UserGroup IDs mapped to their most recent time stamps
    ugIds = sharedWiths.flatMap(_.getAs[SharedWith](SHARED_WITH).map { sw =>
      Seq(sw.readOnly, sw.readWrite).flatten.flatMap(_.group.map(_ -> sw.ts))
    })
    ug2TimeStamp = ugIds.flatten.groupBy(_._1).mapValues(_.map(_._2).max)

    // lookup the UserGroups given their IDs ("application-level join")
    cGroup <- groupColl()
    ugs <- cGroup.find(d :~ ID -> (d :~ "$in" -> ug2TimeStamp.keys)).coll[UserGroup, Seq]()
    shareeUserIds = ugs.flatMap(_.userIds).flatten.toSet

    // get all the usernames of the shared-with users ("sharees")
    cUsers <- dbColl()
    sharees <- cUsers.find(d :~ User.ID -> (d :~ "$in" -> shareeUserIds)).coll[User, Seq]()
    shareeId2Username = sharees.flatMap(u => u.userData.username.map(u.id -> _)).toMap

  } yield {

    // combine usernames and emails of shared-with people into a single collection of "sharee" strings
    val sharee2TimeStamp = ugs.flatMap { ug =>
      val shareeStrings = ug.emails.getOrElse(Set.empty[String]) ++
                          ug.userIds.fold(Set.empty[String])(_.flatMap(shareeId2Username.get).map("@" + _))
      shareeStrings.map(_ -> ug2TimeStamp(ug.id))
    }

    // map each sharee to its most recent usage, sort descending, and then return the most recent 50
    sharee2TimeStamp.groupBy(_._1).mapValues(_.map(_._2).max).toSeq.sortBy(-_._2).map(_._1).take(50)
  }

  /** Removes user group given ID. */
  def deleteGroup(groupId: ObjectId): Future[Unit] = for {
    c <- groupColl()
    wr <- c.remove(d :~ ID -> groupId)
    _ <- wr.failIfError
  } yield ()


  /** 
    * Search users by prefix for autosuggest for two cases:
    *   1. if requred to check all users - for sharing purposes
    *   2. if required to check only users with public marks - for marks search purposes
    *
    * @param prefix  Username prefix to search.
    * @param userId  If `hasShared` is true, this parameter is used to filter out marks owned by this user.
    * @param email  Required only if `hasShared` is true, but why?
    * @param hasShared  If true, filter to only get usernames with public marks.
    *
    * TODO: probably eventually need to implement pagination
    */
  def searchUsernamesByPrefix(
                               prefix: String,
                               userId: UUID,
                               email: Option[String],
                               hasShared: Boolean = false): Future[Seq[UserAutosuggested]] = {

    // check if username exists to skip empty usernames if data migration wasn't successfull,
    // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
    val filterUserNamesByPrefixQuery = d :~ UNAMELOWx -> (d :~ "$exists" -> 1) :~
                                            UNAMELOWx -> BSONRegex(".*" + prefix.toLowerCase + ".*", "i")

    if (!hasShared) {
      // simple search by username prefix for sharing purposes, does not apply any filters or validation
      for {
        c <- dbColl()
        users <- c.find(filterUserNamesByPrefixQuery).sort(d :~ UNAMELOWx -> 1).coll[User, Seq]().map {
          _.map(u => UserAutosuggested(u.id, u.userData.username.getOrElse("")))
        }
      } yield  users.filterNot(_.id == userId)

    } else {

      // TODO: what are these?  are the operation words?  or can they be just any random identifier?
      val set = "set"
      val dollarSet = s"$$$set."

      // logic performs 3 actions
      // 1) aggregates users by username if they shared private marks with operator
      // 2) aggregates users if users have public marks
      // 3) concats 2 results, removes duplicates and own user id
      for {
        cGroup <- groupColl()
        cMarks <- marksColl()
        cUsers <- dbColl()

        // TODO: lots of copied code in the next 2 blocks.  can't they be combined somehow?

        // 1) aggregates users by username if they shared private marks with operator
        userWithSharedToUserMarks <- {

          import cGroup.BatchCommands.AggregationFramework
          import AggregationFramework._

          // gatch to find email of operator in usergroups
          cGroup.aggregate(firstOperator = Match(d :~ EMAILS -> email),
            otherOperators = List(
              // get marks ids of found usergroups
              Lookup(cMarks.name, SHROBJSID, Mark.ID, entriesFoundCollName),
              // get userIds of found marks
              Lookup(cUsers.name, entriesFoundCollUserId, User.ID, usersFoundCollName),
              // unwind joined users https://docs.mongodb.com/manual/reference/operator/aggregation/unwind/
              Unwind(usersFoundCollName, None, Some(true)),
              // project only required fields
              Project(d :~ User.ID -> usersFoundCollId :~
                           UNAME -> usersFoundCollUserName :~ // TODO: should this be UNAMEx and below where $username is used?
                           UNAMELOWx -> usersFoundCollUserNameLower ), // TODO: why are both UNAMEx and UNAMELOWx necessary?
              // grouping to set by id to remove user duplications on database level
              Group(BSONString(User.ID))(set -> AddToSet(d :~ User.ID -> ("$" + User.ID) :~
                                                              UNAME -> ("$" + UNAME) :~ // TODO: UNAMEx?
                                                              UNAMELOW -> ("$" + UNAMELOWx))),
              // unwind set variable
              Unwind(set, None, Some(true)),
              // project only required fields
              Project(d :~ User.ID -> (dollarSet + User.ID) :~
                           UNAME -> (dollarSet + UNAME) :~ // TODO: UNAMEx?
                           UNAMELOWx -> (dollarSet + UNAMELOW)), // TODO: or UNAMELOW?
              // filter user names by username suffix from request
              Match(filterUserNamesByPrefixQuery)
            )).map(_.head[UserAutosuggested])
        }

        // 2) Aggregates users if users have public marks
        usersWithPublicMarks <- {
          import cMarks.BatchCommands.AggregationFramework
          import AggregationFramework._

          // TODO: need to add Level.LISTED here
          def swDoc(rorwx: String): BSONDocument = d :~
            rorwx -> (d :~ "$exists" -> 1) :~
            s"$rorwx.$GROUP" -> (d :~ "$exists" -> 1) :~ // TODO: this was a bug (previously: READONLYLEVEL)
            s"$rorwx.$LEVEL" -> Level.PUBLIC.id

          // get marks with Public share level
          cMarks.aggregate(
            firstOperator = Match(d :~ SHARED_WITH -> (d :~ "$exists" -> 1) :~ curnt :~
                                       "$or" -> BSONArray(swDoc(READONLYx), swDoc(READWRITEx))),
            otherOperators = List(
              // project only userId field to find users
              Project(d :~ USR -> 1),
              // get users who has public marks by found marks id
              Lookup(cUsers.name, USR, User.ID, usersFoundCollName),
              // unwind joined users https://docs.mongodb.com/manual/reference/operator/aggregation/unwind/
              Unwind(usersFoundCollName, None, Some(true)),
              // project only required fields
              Project(d :~ User.ID -> usersFoundCollId :~
                           UNAME -> usersFoundCollUserName :~ // TODO: UNAMEx?  but why not just only use UNAMELOWx?
                           UNAMELOWx -> usersFoundCollUserNameLower),
              // grouping to set by id to remove user duplications on database level
              Group(BSONString(User.ID))(set -> AddToSet(d :~ User.ID -> ("$" + User.ID) :~
                                                              UNAME -> ("$" + UNAME) :~
                                                              UNAMELOW -> ("$" + UNAMELOWx))),
              // unwind set variable
              Unwind(set, None, Some(true)),
              // project only required fields
              Project(d :~ User.ID -> (dollarSet + User.ID) :~
                           UNAME -> (dollarSet + UNAME) :~ // TODO: UNAMEx?
                           UNAMELOWx -> (dollarSet + UNAMELOW)),
              // filter user names by username suffix from request
              Match(filterUserNamesByPrefixQuery)
          )).map(_.head[UserAutosuggested])
        }

        /*TODO maybe join 2 different collections aggregations somehow but it seems
          TODO it requires to rewrite aggregate functions for use of rawCommand on db not on collection
          val ag1 = Aggregate("userGroup", usersWithSharedMarks)
          val ag2 = Aggregate("entries", usersWithPublicMarks)
          for {
            dbd <- db()
            // something like
            users <- dbd.runCommand(Group("id", Seq(ag1,ag2)))
          } yield users
        */

        // 3) Concats 2 results, removes duplicates and own user id
      } yield (userWithSharedToUserMarks ++ usersWithPublicMarks).distinct.filterNot(_.id == userId).sortBy(_.username)
    }
  }

  def retrieveUsername(
                       userId: UUID,
                       prefix: String,
                       hasShared: Boolean = false): Future[Seq[String]] = {
    if (!hasShared) {
      // simple search by username prefix for sharing purposes, does not apply any filters or validation

      for {
        c <- dbColl()

        sel = d :~
          // check if username exists to skip empty usernames if data migration wasn't successfull,
          UNAMELOWx -> (d :~ "$exists" -> 1) :~
          // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
          UNAMELOWx -> BSONRegex(".*" + prefix.toLowerCase + ".*", "i")

        users <- c.find(sel)
          .sort(d :~ UNAMELOWx -> 1).coll[User, Seq]()
          .map(_.collect {
            case u: User if u.id != userId && u.userData.username.isDefined =>
              u.userData.username.get
          })
      } yield users
    } else Future.successful(Nil)
  }
}