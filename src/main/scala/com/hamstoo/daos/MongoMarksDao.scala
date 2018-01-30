package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.Shareable.{N_SHARED_FROM, N_SHARED_TO, SHARED_WITH}
import com.hamstoo.models._
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for MongoDB `entries` (o/w known as "marks") collection.
  */
class MongoMarksDao(db: () => Future[DefaultDB])
                   (implicit userDao: MongoUserDao, pagesDao: MongoPagesDao) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  val collName: String = "entries"
  private val dbColl: () => Future[BSONCollection] = () => db().map(_ collection collName)
  private def dupsColl(): Future[BSONCollection] = db().map(_ collection "urldups")

  // leave this here as an example of how to perform data migration
  if (scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)) {
    Await.result(for {
      c <- dbColl()
      _ = logger.info(s"Performing data migration for `$collName` collection")
      // put actual data migration code here
    } yield (), 373 seconds)
  } else logger.info(s"Skipping data migration for `$collName` collection")

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: Nil) % s"bin-$USR-1-$TIMETHRU-1" ::
    // the following two indexes are set to unique to prevent messing up timelines of mark/entry states
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    // findMissingReprs indexes
//    Index(PUBREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt)) % s"bin-$PUBREPR-1-partial-$TIMETHRU" ::
//    Index(PRVREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PAGE -> (d :~ "$exists" -> true))) %
//      s"bin-$PRVREPR-1-partial-$TIMETHRU-$PAGE" ::
//    Index(USRREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt)) % s"bin-$USRREPR-1-partial-$TIMETHRU" ::
    // findMissingExpectedRatings (partial) indexes
//    Index(PUBESTARS -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PUBREPR -> (d :~ "$exists" -> true))) %
//      s"bin-$PUBESTARS-1-partial-$TIMETHRU-$PUBREPR" ::
//    Index(PRIVESTARS -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PRVREPR -> (d :~ "$exists" -> true))) %
//      s"bin-$PRIVESTARS-1-partial-$TIMETHRU-$PRVREPR" ::
    // text index (there can be only one per collection)
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: SUBJx -> Text :: TAGSx -> Text :: COMNTx -> Text :: Nil) %
      s"bin-$USR-1-$TIMETHRU-1--txt-$SUBJx-$TAGSx-$COMNTx" ::
    Index(TAGSx -> Ascending :: Nil) % s"bin-$TAGSx-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 389 seconds)

  private val dupsIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(USRPRFX -> Ascending :: URLPRFX -> Ascending :: Nil) % s"bin-$USRPRFX-1-$URLPRFX-1" ::
    Nil toMap;
  Await.result(dupsColl().map(_.indexesManager.ensure(dupsIndxs)), 289 seconds)

  /** Saves a mark to the storage or updates if the user already has a mark with such URL. */
  def insert(mark: Mark): Future[Mark] = {
    logger.debug(s"Inserting mark ${mark.id}")
    for {
      c <- dbColl()
      wr <- c insert mark
      _ <- wr failIfError
    } yield {
      logger.debug(s"Mark: ${mark.id} successfully inserted")
      mark
    }
  }

  /**
    * Inserts existing marks from a stream.  If they are duplicates of pre-existing marks, repr-engine will
    * merge them.
    */
  def insertStream(marks: Stream[Mark]): Future[Int] = {
    logger.debug(s"Inserting stream of marks")
    for {
      c <- dbColl()
      now = TIME_NOW
      ms = marks.map(_.copy(timeFrom = now)).map(Mark.entryBsonHandler.write) // map each mark into a `BSONDocument`
      wr <- c.bulkInsert(ms, ordered = false)
    } yield {
      val count = wr.totalN
      logger.debug(s"$count marks were successfully inserted")
      count
    }
  }

  /** Must be used only for private page */
  def insertPage(page: Page): Future[Page] = {
    logger.debug("Inserting page...")
    for {
      pg <- pagesDao.insertPage(page)
      _ <- changePendingPageNumber(page.userId, page.id)
    } yield {
      logger.debug("Page was inserted")
      page
    }
  }

  /** Must be used only for private page */
  def removePage(page: Page): Future[Unit] = {
    logger.debug("Removing page")

    for {
      _ <- pagesDao.removeSinglePage(page)
      _ <- changePendingPageNumber(page.userId, page.id, increment = false)
    } yield {
      logger.debug("Page was removed")
    }
  }

  /**
    * Retrieves a mark by ID, ignoring whether or not the user is authorized to view the mark, which means the
    * calling code must perform this check itself.
    * @param id        Requested mark ID.
    * @param timeThru  TimeThru of requested mark.  Defaults to INF_TIME--i.e. current revision of mark.
    * @return          None if no such mark is found.
    */
  def retrieveInsecure(id: String, timeThru: TimeStamp = INF_TIME): Future[Option[Mark]] = {
    logger.debug(s"Retrieving (insecure) mark $id")
    for {
      c <- dbColl()
      opt <- c.find(d :~ ID -> id :~ TIMETHRU -> timeThru).one[Mark]
    } yield {
      logger.debug(s"Mark ${opt.map(_.id)} retrieved (insecure)")
      opt
    }
  }

  /** Retrieves a mark by user and ID, None if not found or not authorized. */
  def retrieve(user: Option[User], id: String, timeThru: TimeStamp = INF_TIME): Future[Option[Mark]] = {
    logger.debug(s"Retrieving mark $id for user ${user.map(_.id)}")
    for {
      mInsecure <- retrieveInsecure(id, timeThru = timeThru)
      authorizedRead <- mInsecure.fold(Future.successful(false))(_.isAuthorizedRead(user))
    } yield mInsecure match {
      case Some(m) if authorizedRead => logger.debug(s"Mark $id successfully retrieved"); Some(m)
      case Some(_) => logger.info(s"User $user unauthorized to view mark $id"); None
      case None => logger.debug(s"Mark $id not found"); None
    }
  }

  /** Retrieves all current marks for the user, sorted by `timeFrom` descending. */
  def retrieve(user: UUID): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving marks by user $user")
    for {
      c <- dbColl()
      seq <- c.find(d :~ USR -> user :~ curnt).sort(d :~ TIMEFROM -> -1).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} marks were successfully retrieved")
      seq
    }
  }

  /** Retrieves all marks by ID, including previous versions, sorted by timeFrom, descending.  See retrieveInsecure. */
  def retrieveInsecureHist(id: String): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving history of mark $id")
    for {
      c <- dbColl()
      seq <- c.find(d :~ ID -> id).sort(d :~ TIMEFROM -> -1).coll[Mark, Seq]()
    } yield {
      //val filtered = seq.filter(_.isAuthorizedRead(user))
      logger.debug(s"${seq.size} marks were successfully retrieved")
      seq
    }
  }

  /**
    * Retrieves a current mark by user and URL, None if not found.  This is used in the Chrome extension via the
    * backend's `MarksController` to quickly get the mark for an active tab.  Eventually we'll probably want to
    * implement more complex logic based on representations similar to repr-engine's `dupSearch`.
    */
  def retrieveByUrl(url: String, user: UUID): Future[Option[Mark]] = {
    logger.debug(s"Retrieving marks by URL $url and user $user")
    for {
      // find set of URLs that contain duplicate content to the one requested
      cDups <- dupsColl()
      setDups <- cDups.find(d :~ USRPRFX -> user.toString.binPrfxComplement :~ URLPRFX -> url.binaryPrefix).coll[UrlDuplicate, Set]()
      urls = Set(url).union(setDups.filter(ud => ud.userId == user && ud.url == url).flatMap(_.dups))

      // find all marks with those URL prefixes
      c <- dbColl()
      prfxIn = d :~ "$in" -> urls.map(_.binaryPrefix)
      seq <- c.find(d :~ USR -> user :~ URLPRFX -> prfxIn :~ curnt).coll[Mark, Seq]()

    } yield {

      // filter/find down to a single (optional) mark
      val optMark = seq.find(_.mark.url.exists(urls.contains))
      logger.debug(s"$optMark mark was successfully retrieved")
      optMark
    }
  }

  /**
    * Map each URL to the other in the `urldups` collection.  The only reason this method (currently) returns a
    * Future[String] rather than a Future[Unit] is because of where it's used in repr-engine.
    */
  def insertUrlDup(user: UUID, url0: String, url1: String): Future[String] = {
    logger.debug(s"Inserting URL duplicates for $url0 and $url1")
    for {
      c <- dupsColl()

      // database lookup to find candidate dups via indexed prefixes
      candidates = (url: String) =>
        c.find(d :~ USRPRFX -> user.toString.binPrfxComplement :~ URLPRFX -> url.binaryPrefix).coll[UrlDuplicate, Set]()
      candidates0 <- candidates(url0)
      candidates1 <- candidates(url1)

      // narrow down candidates sets to non-indexed (non-prefix) values
      optDups = (url: String, candidates: Set[UrlDuplicate]) =>
        candidates.find(ud => ud.userId == user && ud.url == url)
      optDups0 = optDups(url0, candidates0)
      optDups1 = optDups(url1, candidates1)

      // construct a new UrlDuplicate or an update to the existing one
      newUD = (urlKey: String, urlVal: String, optDups: Option[UrlDuplicate]) =>
        optDups.fold(UrlDuplicate(user, urlKey, Set(urlVal)))(ud => ud.copy(dups = ud.dups + urlVal))
      newUD0 = newUD(url0, url1, optDups0)
      newUD1 = newUD(url1, url0, optDups1)

      // update or insert if not already there
      _ <- c.update(d :~ ID -> newUD0.id, newUD0, upsert = true)
      _ <- c.update(d :~ ID -> newUD1.id, newUD1, upsert = true)
    } yield ""
  }

  /** Retrieves all current marks for the user, constrained by a list of tags. Mark must have all tags to qualify. */
  def retrieveTagged(user: UUID, tags: Set[String]): Future[Seq[Mark]] = {
    logger.debug(s"Retrieve tagged marks for user $user and tags $tags")
    for {
      c <- dbColl()

      sel0 = d :~ USR -> user :~ TAGSx -> (d :~ "$all" -> tags) :~ curnt
      sel1 = if (tags.isEmpty) sel0 else sel0 :~ TAGSx -> (d :~ "$all" -> tags)

      seq <- (c find sel1 sort d :~ TIMEFROM -> -1).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} tagged marks were successfully retrieved")
      seq
    }
  }

  /**
    * Retrieves all current marks with representations for the user, constrained by a list of tags.  Mark must have
    * all tags to qualify.
    * @param user  Only marks for this user will be returned/searched.
    * @param tags  Returned marks must have all of these tags, default to empty set.
    */
  def retrieveRepred(user: UUID, tags: Set[String] = Set.empty[String]): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving represented marks for user $user and tags $tags")
    for {
      c <- dbColl()

      reprs = d :~ REPRS -> (d :~ "$not" -> (d :~ "$size" -> 0))

      // maybe we should $and instead of $or
      sel0 = d :~ USR -> user :~ curnt :~ reprs // TODO: should `curnt` be moved into `reprs` to utilize indexes?

      sel1 = if (tags.isEmpty) sel0 else sel0 :~ TAGSx -> (d :~ "$all" -> tags)

      // todo: provide excluding in issue-222
      seq <- c.find(sel1 /*, searchExcludedFields */).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} represented marks were successfully retrieved")
      seq.map { m => m.copy(aux = m.aux.map(_.cleanRanges)) }
    }
  }

  /** Retrieves all tags existing in all current marks for the given user. */
  def retrieveTags(user: UUID): Future[Set[String]] = {
    logger.debug(s"Retrieve tags for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ curnt
      docs <- c.find(sel, d :~ TAGSx -> 1 :~ "_id" -> 0).coll[BSONDocument, Set]()
    } yield for {
      doc <- docs // foreach returned document and foreach mark.tags
      tag <- doc.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGSx.split(raw"\.")(1)) getOrElse Set.empty
    } yield {
      logger.debug(s"Successfully retrieved tag $tag")
      tag // yields each tag separately, but then combines them into a set at the end
    }
  }

  // exclude these fields from the returned results of search-related methods to conserve memory during search
  // TODO: implement a MSearchable base class so that users know they're dealing with a partially populated Mark
  // (should have looked more closely at hamstoo.SearchService when choosing these fields; see issue #222)
  val searchExcludedFields: BSONDocument = d :~ /*(PAGES -> 0) :~ */(URLPRFX -> 0) :~ (TOTVISx -> 0) :~ (TOTBGx -> 0) :~
    (MERGEID -> 0) :~ (COMNTENCx -> 0)

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Mark state must be
    * current and have all tags to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Mark]] = {
    logger.debug(s"Searching for marks for user $user by text query '$query' and tags $tags")
    for {
      c <- dbColl()
      sel0 = d :~ USR -> user :~ curnt

      // this projection doesn't have any effect without this selection
      searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
      searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

      seq <- c.find(sel0 :~ searchScoreSelection,
                    /* searchExcludedFields :~ */ searchScoreProjection)/*.sort(searchScoreProjection)*/
        .coll[Mark, Seq]()

    } yield {
      val filtered = seq.view.filter { m => tags.forall(t => m.mark.tags.exists(_.contains(t))) }
        .map { m => m.copy(aux = m.aux.map(_.cleanRanges)) }
        .force
      logger.debug(s"${filtered.size} marks were successfully retrieved (${seq.size - filtered.size} were filtered out per their labels)")
      filtered
    }
  }

  /**
    * Updates current state of a mark with user-provided MarkData, looking the mark up by user and ID.
    * Returns new current mark state.  Do not attempt to use this function to update non-user-provided data
    * fields (i.e. non-MarkData).
    *
    * TODO: only update timestamps and insert a new mark when sufficiently different from old mark
    */
  def update(user: Option[User], id: String, mdata: MarkData): Future[Mark] = for {
    c <- dbColl()
    _ = logger.info(s"Updating mark $id")

    // test write permissions
    mOld <- for {
      mSecure <- retrieve(user, id)
      authorizedWrite <- mSecure.fold(Future.successful(false))(_.isAuthorizedWrite(user))
    } yield mSecure match {
      case Some(m) if authorizedWrite => m
      case Some(_) => throw new Exception(s"User $user unauthorized to modify mark $id")
      case None => throw new Exception(s"Unable to find mark $id for updating")
    }

    // be sure to not use `user`, which could be different from `mOld.userId` if the the mark has been shared
    sel = d :~ USR -> mOld.userId :~ ID -> id :~ curnt
    now: Long = TIME_NOW
    wr <- c.update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now))
    _ <- wr.failIfError


    _ = logger.debug(s"0 REPRS: ${mOld.reprs}")
    // if the URL has changed then discard the old public repr (only the public one though as the private one is
    // based on private user content that was only available from the browser extension at the time the user first
    // created it)
    reprs0 = if (mdata.equalsPerPubRepr(mOld.mark)) mOld.reprs else mOld.reprs.filterNot(_.reprType == Representation.PUBLIC)

    // if user-generated content has changed then discard the old user repr (also see unsetUserContentReprId below)
    reprs1 = if (mdata.equalsPerUserRepr(mOld.mark)) reprs0 else reprs0.filterNot(_.reprType == Representation.USERS)

    _ = logger.debug(s"REPRS: $reprs1")

    newMk = mOld.copy(mark = mdata, reprs = reprs1, timeFrom = now, timeThru = INF_TIME, modifiedBy = user.map(_.id))

    wr <- c.insert(newMk)
    _ <- wr.failIfError
  } yield {
    logger.debug(s"Mark: $id was successfully updated...")
    newMk
  }

  /**
    * R sharing level must be at or above RW sharing level.
    * Updating RW permissions with higher than existing R permissions will raise R permissions as well.
    * Updating R permissions with lower than existing RW permissions will reduce RW permissions as well.
    *
    * TODO: This method should be moved into a MongoShareableDao class, similar to MongoAnnotationDao.
    */
  def updateSharedWith(m: Mark, nSharedTo: Int,
                       readOnly : Option[(SharedWith.Level.Value, Option[UserGroup])],
                       readWrite: Option[(SharedWith.Level.Value, Option[UserGroup])]): Future[Mark] = {
    logger.debug(s"Sharing mark ${m.id} with $readOnly and $readWrite")
    val ts = TIME_NOW // use the same time stamp everywhere
    val so = Some(UserGroup.SharedObj(m.id, ts))
    def saveGroup(opt: Option[UserGroup]): Future[Option[UserGroup]] =
      opt.fold(Future.successful(Option.empty[UserGroup]))(ug => userDao.saveGroup(ug, so).map(Some(_)))

    for {
      // these can return different id'ed groups than were passed in (run these sequentially so that if they're the
      // same only one instance will be written to the database)
      ro <- saveGroup(readOnly .flatMap(_._2))
      rw <- saveGroup(readWrite.flatMap(_._2))
      sw = SharedWith(readOnly  = readOnly .flatMap(x => ShareGroup.xapply(x._1, ro)),
                      readWrite = readWrite.flatMap(x => ShareGroup.xapply(x._1, rw)), ts = ts)

      // this isn't exactly right as it's double counting any previously shared-with emails
      //nSharedTo <- sw.emails.map(_.size)

      c <- dbColl()

      // be sure not to select userId field here as different DB models use name that field differently: userId/usrId
      sel = d :~ ID -> m.id :~ curnt
      wr <- {
        import UserGroup.sharedWithHandler
        val set = d :~ "$set" -> (d :~ SHARED_WITH -> sw)
        val inc = d :~ "$inc" -> (d :~ N_SHARED_FROM -> 1 :~ N_SHARED_TO -> nSharedTo)
        c.findAndUpdate(sel, set :~ inc, fetchNewObject = true)
      }
      _ <- if (wr.lastError.exists(_.n == 1)) Future.successful {} else {
        val msg = s"Unable to findAndUpdate Shareable ${m.id}'s shared with; wr.lastError = ${wr.lastError.get}"
        Logger.error(msg)
        Future.failed(new NoSuchElementException(msg))
      }
    } yield {
      logger.debug(s"Mark ${m.id} was successfully shared with $sw")
      wr.result[Mark].get
    }
  }

  /**
    * Updates a mark's subject and URL only.  No need to maintain history in this case because all info is preserved.
    * Only marks with missing URL are selected and current subject is moved to URL field.
    */
  def updateSubject(user: UUID, id: String, newSubj: String): Future[Int] = {
    logger.debug(s"Updating subject '$newSubj' (and URL) for mark $id")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> id :~ curnt :~ URLx -> (d :~ "$exists" -> false)
      doc <- c.find(sel, d :~ SUBJx -> 1 :~ "_id" -> 0).one[BSONDocument]
      oldSubj = doc.get.getAs[BSONDocument](MARK).get.getAs[String](SUBJx.split(raw"\.")(1)).getOrElse("")
      _ = logger.info(s"Updating subject from '$oldSubj' to '$newSubj' for mark $id")
      wr <- c.update(sel, d :~ "$set" -> (d :~ SUBJx -> newSubj :~ URLx -> oldSubj))
      _ <- wr.failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"$count marks' subjects were successfully updated")
      count
    }
  }

  /**
    * Merge two marks by setting their `timeThru`s to the time of execution and inserting a new mark with the
    * same `timeFrom`.
    */
  def merge(oldMark: Mark, newMark: Mark, now: Long = TIME_NOW): Future[Mark] = {
    val oldMarkId = oldMark.id
    val newMarkId = newMark.id

    logger.debug(s"Merge marks (oldOne: $oldMarkId and newOne: $newMarkId")

    for {
      c <- dbColl()

      // delete the newer mark and merge it into the older/pre-existing one (will return 0 if newMark not in db yet)
      _ <- delete(newMark.userId, Seq(newMarkId), now = now, mergeId = Some(oldMarkId), ensureDeletion = false)

      // merge user page
      _ <- pagesDao.mergeUserPages(newMark.userId, oldMarkId, newMarkId)

      // merge public page
      _ <- pagesDao.mergePublicPages(newMark.userId, oldMarkId, newMarkId)

      // merge private page
      _ <- pagesDao.mergePrivatePages(newMark.userId, oldMarkId, newMarkId)

      mergedMk = oldMark.merge(newMark).copy(timeFrom = now, timeThru = INF_TIME)

      sel = d :~ USR -> mergedMk.userId :~ ID -> mergedMk.id :~ curnt

      mod = d :~ "$set" -> (d :~ TIMETHRU -> now)

      // don't do anything if there wasn't a meaningful change to the old mark
      _ <- if (oldMark equalsIgnoreTimeStamps mergedMk) Future.unit else for {

        wr <- c.update(sel, mod)
        _ <- wr.failIfError

        wr <- c.insert(mergedMk)
        _ <- wr.failIfError

      } yield ()
    } yield {
      logger.debug(s"Marks $oldMarkId and $newMarkId was successfully merged in ${mergedMk.id}")
      mergedMk
    }
  }

  /**
    * Renames one tag in all user's marks that have it.
    * Returns updated mark states number.
    */
  def updateTag(user: UUID, tag: String, rename: String): Future[Int] = {
    logger.debug(s"Updating all '$tag' tags to '$rename' for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ TAGSx -> tag
      wr <- c.update(sel, d :~ "$set" -> (d :~ s"$TAGSx.$$" -> rename), multi = true)
      _ <- wr.failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"$count marks' tags were successfully updated")
      count
    }
  }

  /** Appends `time` to either `.tabVisible` or `.tabBground` array of a mark. */
  def addTiming(user: UUID, id: String, time: RangeMils, foreground: Boolean): Future[Unit] = for {
    c <- dbColl()
    sel = d :~ USR -> user :~ ID -> id :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ (if (foreground) TABVISx else TABBGx) -> time))
    _ <- wr.failIfError
  } yield ()

  /**
    * Updates all user's marks with new user ID, effectively moving them to another user.
    * Returns the number of mark states moved.
    */
  def move(thisUser: UUID, thatUser: UUID): Future[Int] = {
    logger.debug(s"Moving marks from user $thisUser to user $thatUser")
    for {
      c <- dbColl()
      wr <- c update(d :~ USR -> thatUser, d :~ "$set" -> (d :~ USR -> thisUser), multi = true)
      _ <- wr failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"$count were successfully moved from user $thisUser to user $thatUser")
      count
    }
  }

  /** Updates `timeThru` of a set of current marks (selected by user and a list of IDs) to time of execution. */
  def delete(user: UUID,
             ids: Seq[String],
             now: Long = TIME_NOW,
             mergeId: Option[String] = None,
             ensureDeletion: Boolean = true): Future[Int] = {
    logger.debug(s"Deleting marks for user $user: $ids")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
      mrg = mergeId.map(d :~ MERGEID -> _).getOrElse(d)
      wr <- c.update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now :~ mrg), multi = true)
      _ <- wr.failIfError
      _ <- if (wr.nModified == ids.size || !ensureDeletion) Future.successful {} else {
        val msg = s"Unable to delete marks; ${wr.nModified} out of ${ids.size} were successfully deleted; first attempted (at most) 5: ${ids.take(5)}"
        logger.error(msg)
        Future.failed(new NoSuchElementException(msg))
      }
    } yield {
      val count = wr.nModified
      logger.debug(s"$count marks were successfully deleted")
      count
    }
  }

  /** Removes a tag from all user's marks that have it. */
  def deleteTag(user: UUID, tag: String): Future[Int] = {
    logger.debug(s"Deleting tag '$tag' from all user's marks for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ TAGSx -> tag
      wr <- c.update(sel, d :~ "$pull" -> (d :~ TAGSx -> tag), multi = true)
      _ <- wr.failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"Tag '$tag' was removed from $count marks")
      count
    }
  }

  /** Adds a set of tags to each current mark from a list of IDs. */
  def tag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = {
    logger.debug(s"Adding tags to marks for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
      wr <- c update(sel, d :~ "$push" -> (d :~ TAGSx -> (d :~ "$each" -> tags)), multi = true)
      _ <- wr.failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"Tags were added to $count marks")
      count
    }
  }

  /** Removes a set of tags from each current mark from a list of IDs if they have any of the tags. */
  def untag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = {
    logger.debug(s"Removing tags from marks for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
      wr <- c.update(sel, d :~ "$pull" -> (d :~ TAGSx -> (d :~ "$in" -> tags)), multi = true)
    } yield {
      val count = wr.nModified
      logger.debug(s"Tags were removed from $count marks")
      count
    }
  }

  /**
    * Retrieves a list of n marks that require representations. Intentionally not filtering for `curnt` marks.
    *
    * The following MongoDB shell command should show that this query is using two indexes via an "OR" inputStage.
//    *   db.entries.find({$or:[{timeThru:NumberLong("9223372036854775807"), pubRepr:{$exists:0}},
//    *                         {timeThru:NumberLong("9223372036854775807"), privRepr:{$exists:0}, page:{$exists:1}}]}).explain()
    */
  def findMissingReprs(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing representations")
    for {
      c <- dbColl()

      // `curnt` must be part of selPub & selPriv, rather than appearing once outside the $or, to utilize the indexes
      selPriv = d :~ curnt :~ PENDING_PAGES -> (d :~ "$ne" -> 0)
      selPub = d :~ curnt :~ REPRS -> (d :~ "$not" -> (d :~ "$elemMatch" -> (d :~ REPR_TYPE -> Representation.PUBLIC)))
      selUser = d :~ curnt :~ REPRS -> (d :~ "$not" -> (d :~ "$elemMatch" -> (d :~ REPR_TYPE -> Representation.USERS)))

      sel = d :~ "$or" -> Seq(selPub, selPriv,  selUser)
      seq <- c.find(sel).coll[Mark, Seq](n)
    } yield {
      logger.debug(s"${seq.size} marks with missing representations were retrieved")
      seq
    }
  }

  def changePendingPageNumber(userId: UUID, id: String, increment: Boolean = true): Future[Unit] = {
    logger.debug("Incrementing number of pending pages")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id :~ curnt
      mod = if (increment) d :~ "$inc" -> (d :~ PENDING_PAGES -> 1) else d :~ "$inc" -> (d :~ PENDING_PAGES -> -1)

      ur <- c.update(sel, mod)
      _ <- ur failIfError
    } yield {
      logger.debug(s"Pending page number was changed for user: $userId and mark: $id")
    }
  }

  def findMissingPublicReprs(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing public representations")

    for {
      c <- dbColl()

      sel = d :~
        curnt :~
        REPRS -> (d :~ "$not" -> (d :~ "$elemMatch" -> (d :~ REPR_TYPE -> Representation.PUBLIC)))

      seq <- c.find(sel).coll[Mark, Seq](n)
    } yield {
      logger.debug(s"${seq.size} marks with missing public representations were retrieved")
      seq
    }
  }

  /**
    * Retrieves a list of n marks that require expected ratings. Intentionally not filtering for `curnt` marks.
    *
    * The following MongoDB shell command should show that this query is using two indexes via an "OR" inputStage.
    *   db.entries.find({$or:[{timeThru:NumberLong("9223372036854775807"), pubExpRating:{$exists:0}, pubRepr:{$exists:1}},
    *                         {timeThru:NumberLong("9223372036854775807"), privExpRating:{$exists:0}, privRepr:{$exists:1}}]}).explain()
    */
  def findMissingExpectedRatings(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing expected ratings")
    for {
      c <- dbColl()

      sel = d :~ curnt :~ REPRS -> (d :~ "$not" -> (d :~ "$size" -> 0)) :~ EXP_RATINGx -> (d :~ "$exists" -> false)

      seq <- c.find(sel).coll[Mark, Seq](n)
    } yield {
      logger.debug(s"${seq.size} marks with missing E[rating]s were retrieved")
      seq
    }
  }

  def saveReprInfo(user: UUID, markId: String, reprInfo: ReprInfo): Future[Unit] = {
    logger.debug(s"Saving Representation info for user: $user of mark: $markId")

    for {
      c <- dbColl()

      sel = d :~ USR -> user :~ ID -> markId
      mod = d :~ "$push" -> (d :~ REPRS -> reprInfo)

      ur <- c.update(sel, mod)
      _ <- ur failIfError
    } yield {
      logger.debug(s"Representation Info was inserted for user: $user of mark: $markId")
    }
  }

  /** Remove a userRepr from a Mark.  Used by MongoAnnotationDao when annotations are created and destroyed. */
  def unsetUserRepr(user: UUID, id: String): Future[Unit] = unsetRepr(user, id, Representation.USERS)

  /** Remove a publicRepr from a Mark.  Used by MongoAnnotationDao when annotations are created and destroyed. */
  def unsetPublicRepr(user: UUID, id: String): Future[Unit] = unsetRepr(user, id, Representation.PUBLIC)

  def unsetPrivateRepr(user: UUID, id: String, reprId: String): Future[Unit] = {
    logger.debug(s"Remove private representation for user: $user of mark: $id")

    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> id
      mod = d :~ "$pull" -> (d :~ REPR_ID -> reprId)
      ur <- c.update(sel, mod)
      _ <- ur failIfError
    } yield {
      logger.debug(s"Private representation $reprId was removed for user: $user of mark: $id")
    }
  }

  def updatePrivateERatingId(user: UUID,
                             markId: String,
                             reprId: String,
                             timeFrom: Long,
                             erId: String): Future[Unit] = {

    logger.debug(s"Updating private expect rating for representation: $reprId of mark: $markId")
    for {
      c <- dbColl()

      sel = d :~
        USR -> user :~
        ID -> markId :~
        REPRS -> (d :~ "$elemMatch" -> (d :~ REPR_ID -> reprId)) :~
        TIMEFROM -> timeFrom

      mod = d :~ "$set" -> (d :~ EXP_RATINGxp -> erId)

      ur <- c.update(sel, mod)
      _ <- ur failIfError
    } yield {
      logger.debug(s"Expect rating was updated, for representation: $reprId of mark: $markId")
    }
  }

  def updatePublicERatingId(user: UUID,
                            markId: String,
                            timeFrom: Long,
                            erId: String): Future[Unit] = updateERatingId(user, markId, Representation.PUBLIC, timeFrom, erId)

  def updateUsersERatingId(user: UUID,
                           markId: String,
                           timeFrom: Long,
                           erId: String): Future[Unit] = updateERatingId(user, markId, Representation.USERS, timeFrom, erId)

  /** Returns true if a mark with the given URL was previously deleted.  Used to prevent autosaving in such cases. */
  def isDeleted(user: UUID, url: String): Future[Boolean] = {
    logger.debug(s"Checking if mark was deleted, for user $user and URL: $url")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ URLPRFX -> url.binaryPrefix :~ TIMETHRU -> (d :~ "$lt" -> INF_TIME)
      seq <- c.find(sel).coll[Mark, Seq]()
    } yield {
      val deleted = seq.exists(_.mark.url.contains(url))
      logger.debug(s"Mark for user: $user and URL: $url, isDeleted = $deleted")
      seq.exists(_.mark.url.contains(url))
    }
  }

  /**
    * Search for a [MarkData] by userId, subject and empty url field for future merge
    * @return - optional [MarkData]
    */
  def findDuplicateSubject(userId: UUID, subject: String): Future[Option[Mark]] = {
    logger.debug(s"Searching for duplicate subject marks for user $userId and subject '$subject'...")
    for {
      c <- dbColl() // TODO: does this query require an index?  or is the "bin-$USR-1-$TIMETHRU-1" index sufficient?
      sel = d :~ USR -> userId :~ SUBJx -> subject :~ URLx -> (d :~ "$exists" -> false) :~ curnt
      opt <- c.find(sel).one[Mark]
    } yield {
      logger.debug(s"Searching for duplicate subject marks finished with result mark ${opt.map(_.id)}")
      opt
    }
  }

  private def updateERatingId(user: UUID,
                              markId: String,
                              reprType: String,
                              timeFrom: Long,
                              erId: String): Future[Unit] = {
    logger.debug(s"Updating $reprType representation expect rating for user: $user of mark: $markId")
    for {
      c <- dbColl()

      sel = d :~
        USR -> user :~
        ID -> markId :~
        REPRS -> (d :~ "$elemMatch" -> (d :~ REPR_TYPE -> reprType)) :~
        TIMEFROM -> timeFrom

      mod = d :~ "$set" -> (d :~ EXP_RATINGxp -> erId)

      ur <- c.update(sel, mod)
      _ <- ur failIfError
    } yield {
      logger.debug(s"$reprType expect rating was updated, for user: $user of mark: $markId")
    }
  }

  private def unsetRepr(user: UUID, id: String, reprType: String): Future[Unit] = {
    logger.debug(s"Removing $reprType representation for user: $user of mark: $id")
    for {
      c <- dbColl()

      sel = d :~ USR -> user :~ ID -> id :~ curnt
      mod = d :~ "$pull" -> (d :~ REPRS -> (d :~ REPR_TYPE -> reprType))

      wr <- c.update(sel, mod)
      _ <- wr.failIfError
      // TODO: should we delete from the representations collection as well?
    } yield {
      logger.debug(s"$reprType representation was removed")
    }
  }
}
