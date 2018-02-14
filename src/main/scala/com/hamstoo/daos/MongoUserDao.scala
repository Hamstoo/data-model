package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.User._
import com.hamstoo.models._
import com.hamstoo.utils.d
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONArray, BSONDocument, BSONDocumentHandler, BSONRegex, BSONValue, Macros}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object MongoUserDao {
  var migrateData = scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)
}

/**
  * Data access object for user accounts.
  */
class MongoUserDao(db: () => Future[DefaultDB]) extends IdentityService[User] {

  val logger: Logger = Logger(classOf[MongoUserDao])
  import com.hamstoo.models.Profile.{loginInfHandler, profileHandler}
  import com.hamstoo.models.UserGroup.{HASH, SHROBJS, userGroupHandler, sharedObjHandler, sharedWithHandler}
  import com.hamstoo.models.Shareable.SHARED_WITH
  import com.hamstoo.utils._

  // get the "users" collection (in the future); the `map` is `Future.map`
  // http://reactivemongo.org/releases/0.12/api/#reactivemongo.api.DefaultDB
  private def dbColl(): Future[BSONCollection] = db().map(_ collection "users")
  private def groupColl(): Future[BSONCollection] = db().map(_ collection "usergroups")
  private def marksColl(): Future[BSONCollection] = db().map(_ collection "entries")

  // temporary data migration code
  // leave this here as an example of how to perform data migration (note that this synchronization doesn't guarantee
  // this code won't be run more than once, e.g. it might be run from backend and repr-engine)
  if (MongoUserDao.migrateData) { synchronized { if (MongoUserDao.migrateData) {
    MongoUserDao.migrateData = false

    Await.result(for {
      c <- dbColl()
      _ = logger.info(s"Performing data migration for `${c.name}` collection")
      sel = d :~ "$or" -> BSONArray(d :~ UNAMELOWx -> (d :~ "$exists" -> 0), d :~ UNAMELOWx -> "")
      nonames <- c.find(sel).coll[User, Seq]()
      newnames <- Future.sequence { nonames.map { u =>
        u.userData.usernameLower.filter(_.trim.nonEmpty).fold {
          u.userData.assignUsername()(this, implicitly[ExecutionContext]).map(ud => u.copy(userData = ud))
        }{ _ => Future.successful(u) } // this can happen if usernameLower isn't set in the db but username is
      }}
      updated <- Future.sequence { newnames.map { u =>
        c.update(d :~ ID -> u.id, d :~ "$set" -> (d :~ UDATA -> u.userData))
      }}
      _ = logger.info(s"Successfully assigned usernames to ${updated.size} users")
    // put actual data migration code here
    } yield (), 363 seconds)
  }}} else logger.info(s"Skipping data migration for `users` collection")

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


  /** Search users by suffix for autosuggest
    * suggests users only if:
    * a user has public marks
    * a user has been in the group with searching user
    * a user has shared marks with searching user
    * @param prefix
    * @param hasSharedMarks, if `false` it search users all over the collection, if `true` it applies filter to get usernames with public marks
    * @param userId required only if hasSharedMarks == true
    * @param email required only if hasSharedMarks == true
    * */
  // Todo debug method
  // Todo add user with private but shared mark and test `usersFromGroups` should return size 1
  // Todo: add test
  // Todo change hardCoded strings to BSONHandler vals names
  def searchUsernamesBySuffix(prefix: String, hasSharedMarks: Boolean = false, userId: Option[UUID], email: Option[String]): Future[Seq[UserAutosuggested]] = {
    // check if username exists to skip empty usernames if data migration wasn't successfull
    // 'i' flag is case insensitive https://docs.moqngodb.com/manual/reference/operator/query/regex/
    val query = BSONDocument(d :~ ( d :~ "userData.usernameLower" -> (d :~ "$exists" -> 1),
      "userData.usernameLower" -> BSONRegex(".*"+prefix.toLowerCase + ".*", "i")))
    for {
      cUsers <- dbColl()
      users <- cUsers.find(query).sort(BSONDocument("userData.username" -> 1)).coll[User, Seq]().map( users =>

        if (!hasSharedMarks) {
          // simple search by username suffix for sharing purposes, does not apply any filters or validation
        users.map(user => UserAutosuggested(user.id, user.userData.username))
      }
    else {
          import UserGroup.sharedWithFieldToString
          import UserGroup.idFieldToString
          // search usernames suffix in users who has public or private marks shared with groups
          users.map(user => UserAutosuggested(user.id, user.userData.username))

         /** find groups which belong to found by suffix usernames and contain share level 1, i.e. private group share
            * with current not completed data structure task 4 step are required
            * check 1 if found username has shared marks with requesting user via usergroups
            * step 1 check if users has shared private marks with other users and collect group ids - privateGroupsIdsOfFoundUsers
            * step 2 filter found groups if they contain requesting user (operator) email
            * step 3 get marks list where sharedWith contains ids of found above groups
            * step 4 get users whom found marks belong to and filter them by suffix sername since same groups can belong to various users
            * check 2 check if users has shared public marks*/
        for {
            cMarks <- marksColl()
            //check 1 step 1
            privateGroupsIdsOfFoundUsers <- cMarks.find(
              d :~ "userId" -> (d :~ "$in" -> users.map(_.id)) :~
                "sharedWith" -> (d :~ "$exists" -> 1) :~
                "sharedWith.readOnly" -> (d :~ "$exists" -> 1) :~
                "sharedWith.readOnly.level" -> 1 :~ curnt) //, d :~ "sharedWith.readOnly.group" -> 1)
              .coll[Mark, Seq]().map(_.map(_.sharedWith.get.readOnly.get.group).distinct)

            // //check 1 step 2: find shareGroups of users found by usernames which are intersected with current user (checked by searching user email exists in emails)
            cGroup <- groupColl()
            privateGroupsIdsOfFoundUsersSharedWithRequestingUser <- cGroup.find(d :~ "id" -> (d :~ "$in" -> privateGroupsIdsOfFoundUsers) :~ (d :~ "emails" -> email),
              d :~ "id" -> 1)
              // and filter here found users with userIds found by privately shared users' marks with searching user
              .coll[String, Seq]() //.map( goupIds => users.filter(user => userIds.contains(user.id) ))

            // now todo only after sharedWith logics completed by Fayaz or Ivan
            //       todo 1 step 4 filter all marks by privateGroupsIdsOfFoundUsersSharedWithRequestingUser
            //     todo 2 step 5 filter marks owners by text
            //       todo 5 write test for this logics
//check 1 step 3: find all marks by privateGroupsIdsOfFoundUsersSharedWithRequestingUser
            marksFoundThatAreSharedWithRequestiongUser <- cMarks.find(
              d :~ "sharedWith" -> (d :~ "$exists" -> 1) :~
                "sharedWith.readOnly" -> (d :~ "$exists" -> 1) :~
                "sharedWith.readOnly.group" -> (d :~ "$in" -> privateGroupsIdsOfFoundUsersSharedWithRequestingUser) :~ curnt) //, d :~ "sharedWith.readOnly.group" -> 1)
              .coll[Mark, Seq]().map(_.map(_.sharedWith.get.readOnly.get.group).distinct)
//check 1 step 4 filter marks owners by suffix
            usersIdFromGroups <- users.fil
            /** check 2: find users' marks if they are shared as PUBLIC by share.level == 3
              */
            userWithPubMarksUsers <- cMarks.find(d :~ curnt :~ d :~ "sharedWith.readOnly.level" -> 3)
              .coll[Mark, Seq]().map(_.map(_.userId)).map(userIdsWithPubMarks => {
              users.filter(user => userIdsWithPubMarks.contains(user.id))
            })
          // concat two results and remove duplications, map to shrinked User version
          } yield (usersIdFromGroups ++ userWithPubMarksUsers).distinct.map(user => UserAutosuggested(user.id, user.userData.username))
    })} yield users
  }
}