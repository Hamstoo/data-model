package com.hamstoo.daos

import java.nio.file.Files
import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.Shareable.{N_SHARED_FROM, N_SHARED_TO, SHARED_WITH}
import com.hamstoo.models._
import com.mohiva.play.silhouette.api.exceptions.NotAuthorizedException
import play.api.Logger
import play.api.libs.Files.TemporaryFile
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
class MongoMarksDao(db: () => Future[DefaultDB])(implicit userDao: MongoUserDao) {

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
    Index(PUBREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt)) % s"bin-$PUBREPR-1-partial-$TIMETHRU" ::
    Index(PRVREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PAGE -> (d :~ "$exists" -> true))) %
      s"bin-$PRVREPR-1-partial-$TIMETHRU-$PAGE" ::
    Index(USRREPR -> Ascending :: Nil, partialFilter = Some(d :~ curnt)) % s"bin-$USRREPR-1-partial-$TIMETHRU" ::
    // findMissingExpectedRatings (partial) indexes
    Index(PUBESTARS -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PUBREPR -> (d :~ "$exists" -> true))) %
      s"bin-$PUBESTARS-1-partial-$TIMETHRU-$PUBREPR" ::
    Index(PRIVESTARS -> Ascending :: Nil, partialFilter = Some(d :~ curnt :~ PRVREPR -> (d :~ "$exists" -> true))) %
      s"bin-$PRIVESTARS-1-partial-$TIMETHRU-$PRVREPR" ::
    // text index (there can be only one per collection)
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: SUBJx -> Text :: TAGSx -> Text :: COMNTx -> Text :: Nil) %
      s"bin-$USR-1-$TIMETHRU-1--txt-$SUBJx-$TAGSx-$COMNTx" ::
    Index(TAGSx -> Ascending :: Nil) % s"bin-$TAGSx-1" ::
    Index(USR -> Ascending :: REFIDx -> Ascending :: TIMETHRU -> Ascending :: Nil) % // can't be unique b/c of nulls
      s"bin-$USR-1-$REFIDx-1-$TIMETHRU-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 389 seconds)

  private val dupsIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(USRPRFX -> Ascending :: URLPRFX -> Ascending :: Nil) % s"bin-$USRPRFX-1-$URLPRFX-1" ::
    Nil toMap;
  Await.result(dupsColl().map(_.indexesManager.ensure(dupsIndxs)), 289 seconds)

  /** Saves a mark to the database. */
  def insert(mark: Mark): Future[Mark] = {
    logger.debug(s"Inserting mark ${mark.id}")
    for {
      c <- dbColl()
      wr <- c.insert(mark)
      _ <- wr.failIfError
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

      // similar to ExtendedWriteResult.failIfError but (1) wr.ok won't always be false when there are errors from
      // a bulk insert and (2) wr is a MultiBulkWriteResult here, not a WriteResult
      _ <- if (wr.writeErrors.isEmpty) Future.successful {}
           else Future.failed(new Exception(wr.writeErrors.mkString("; ")))

    } yield {
      val count = wr.totalN - wr.writeErrors.size
      logger.debug(s"$count marks were successfully inserted")
      count
    }
  }

  /**
    * Retrieves a mark by ID, ignoring whether or not the user is authorized to view the mark, which means the
    * calling code must perform this check itself.
    * @param id        Requested mark ID.
    * @return          None if no such mark is found.
    */
  def retrieveInsecure(id: ObjectId/*, timeThru: TimeStamp = INF_TIME*/): Future[Option[Mark]] =
    retrieveInsecureSeq(id :: Nil/*, timeThru = timeThru*/).map(_.headOption)

  /** Retrieves a list of marks by IDs, ignoring user authorization permissions. */
  def retrieveInsecureSeq(ids: Seq[ObjectId]/*, timeThru: TimeStamp = INF_TIME*/): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving (insecure) ${ids.size} marks; first (at most) 5: ${ids.take(5)}")
    for {
      c <- dbColl()
      seq <- c.find(d :~ ID -> (d :~ "$in" -> ids) :~ curnt/*TIMETHRU -> timeThru*/).coll[Mark, Seq]()
    } yield {
      logger.debug(s"Retrieved (insecure) ${seq.size} marks; first (at most) 5: ${seq.take(5).map(_.id)}")
      seq
    }
  }

  /** Retrieves a mark by user and ID, None if not found or not authorized. */
  def retrieve(user: Option[User], id: ObjectId/*, timeThru: TimeStamp = INF_TIME*/): Future[Option[Mark]] = {
    logger.debug(s"Retrieving mark $id for user ${user.map(_.id)}")
    for {
      mInsecure <- retrieveInsecure(id/*, timeThru = timeThru*/)
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
    logger.info(s"Inserting URL duplicates for $url0 and $url1")
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
      sel = d :~ USR -> user :~ TAGSx -> (d :~ "$all" -> tags) :~ curnt
      seq <- (c find sel sort d :~ TIMEFROM -> -1).coll[Mark, Seq]()
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
      exst = d :~ "$exists" -> true :~ "$nin" -> NON_IDS
      reprs = d :~ "$or" -> BSONArray(d :~ PUBREPR -> exst, d :~ PRVREPR -> exst, d :~ USRREPR -> exst)
      sel0 = d :~ USR -> user :~ curnt :~ reprs // TODO: should `curnt` be moved into `reprs` to utilize indexes?
      sel1 = if (tags.isEmpty) sel0 else sel0 :~ TAGSx -> (d :~ "$all" -> tags)
      seq <- c.find(sel1, searchExcludedFields).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} represented marks were successfully retrieved")
      seq.map { m => m.copy(aux = m.aux.map(_.cleanRanges)) }
    }
  }

  /** Retrieves all of a user's MarkRefs--i.e. marks owned by other users that have been shared with this one. */
  def retrieveRefed(user: UUID): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving referenced marks for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ REFIDx -> (d :~ "$exists" -> true) :~ curnt
      seq <- c.find(sel, searchExcludedFields).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} referenced marks were successfully retrieved")
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
  val searchExcludedFields: BSONDocument = d :~ (PAGE -> 0)  :~ (URLPRFX -> 0) :~ (TOTVISx -> 0) :~ (TOTBGx -> 0) :~
    (MERGEID -> 0) :~ (COMNTENCx -> 0)

  /**
    * Executes a MongoDB Text Index search using text index with sorting in user's marks, constrained by tags.
    * Mark state must be current (i.e. timeThru == INF_TIME) and have all tags to qualify.
    */
  def search(user: UUID, query: String): Future[Seq[Mark]] = search(user :: Nil, query)

  /**
    * Perform Text Index search over the marks of more than one user, which is useful for searching referenced marks,
    * and potentially filter for specific mark IDs.
    */
  def search(users: Seq[UUID], query: String, ids: Set[ObjectId] = Set.empty[ObjectId]): Future[Seq[Mark]] = {
    logger.debug(s"Searching for marks for ${users.size} users (first, at most, 5: ${users.take(5)}) by text query '$query'")
    for {
      c <- dbColl()
      sel0 = d :~ USR -> (d :~ "$in" -> users) :~ curnt

      // this projection doesn't have any effect without this selection
      searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
      searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

      idsFilter = if (ids.isEmpty) d else d :~ ID -> (d :~ "$in" -> ids)

      seq <- c.find(sel0 :~ searchScoreSelection :~ idsFilter,
                    searchExcludedFields :~ searchScoreProjection)/*.sort(searchScoreProjection)*/
        .coll[Mark, Seq]()

    } yield {
      logger.debug(s"Search retrieved ${seq.size} marks")
      seq.map { m => m.copy(aux = m.aux.map(_.cleanRanges)) }
    }
  }

  /**
    * Updates current state of a mark with user-provided MarkData, looking up the mark by user and ID.
    * Returns new current mark state.  Do not attempt to use this function to update non-user-provided data
    * fields (i.e. non-MarkData).
    *
    * It is assumed that if the MarkData's rating.isDefined that the rating field is the only one we have to update.
    */
  def update(user: Option[User], id: String, mdata: MarkData): Future[Mark] = for {
    c <- dbColl()
    _ = logger.info(s"Updating mark $id")

    // test write permissions
    (mOld, updateRef) <- for {
      mInsecure <- retrieveInsecure(id)
      authorizedRead <- mInsecure.fold(Future.successful(false))(_.isAuthorizedRead(user))
      authorizedWrite <- mInsecure.fold(Future.successful(false))(_.isAuthorizedWrite(user))
    } yield mInsecure match {
      case None =>
        throw new NoSuchElementException(s"Unable to find mark $id for updating")
      case Some(_) if !authorizedRead =>
        throw new NotAuthorizedException(s"User ${user.map(_.id)} unauthorized to view mark $id")
      case Some(m) =>

        // update a MarkRef if there's a logged in, non-owner user who just wants to add labels or a rating, if the
        // non-owner is authorized for writing then a change to the set of labels is reflected on the actual mark,
        // but if not then they additional labels will be put on the MarkRef (and only viewable to that non-owner user)
        val updateRef = user.exists(!m.ownedBy(_)) && (!authorizedWrite || mdata.rating.isDefined)

        if (!authorizedWrite && !updateRef)
          throw new NotAuthorizedException(s"User ${user.map(_.id)} unauthorized to modify mark $id")
        (m, updateRef)
    }

    now: Long = TIME_NOW

    // if updateRef is true then just update a mark with a MarkRef, o/w update the actual mark (with the MarkData)
    m <- if (updateRef) updateMarkRef(user.get.id, mOld, mdata)
    else if (mOld.mark == mdata) Future.successful(mOld) else for {

      // be sure to not use `user`, which could be different from `mOld.userId` if the the mark has been shared
      wr <- c.update(d :~ USR -> mOld.userId :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now))
      _ <- wr.failIfError

      // if the URL has changed then discard the old public repr (only the public one though as the private one is
      // based on private user content that was only available from the browser extension at the time the user first
      // created it)
      pubRp = if (mdata.equalsPerPubRepr(mOld.mark)) mOld.pubRepr else None
      // TODO: should pubExpRating be dumped in this case also?

      // if user-generated content has changed then discard the old user repr (also see unsetUserContentReprId below)
      usrRp = if (mdata.equalsPerUserRepr(mOld.mark)) mOld.userRepr else None

      mNew = mOld.copy(mark = mdata, pubRepr = pubRp, userRepr = usrRp,
                       timeFrom = now, timeThru = INF_TIME, modifiedBy = user.map(_.id))

      wr <- c.insert(mNew)
      _ <- wr.failIfError
    } yield mNew
  } yield m

  /** Is this useful? */
  def refSel(user: UUID, refId: ObjectId): BSONDocument = d :~ USR -> user :~ REFIDx -> refId :~ curnt

  /**
    * Retrieves a Mark with a MarkRef (and no MarkData) given its referenced mark ID.  If one doesn't exist
    * in the database, then this method will create it (i.e. upsert).
    */
  def findOrCreateMarkRef(user: UUID, refId: ObjectId): Future[Mark] = for {
    c <- dbColl()
    mOld <- c.find(refSel(user, refId)).one[Mark]
    m <- if (mOld.isDefined) Future.successful(mOld.get) else {
      val ref = MarkRef(refId, tags = Some(Set(MarkData.SHARED_WITH_ME_TAG))) // user can remove this tag later
      val mNew = Mark(user, mark = MarkData("", None), markRef = Some(ref))
      c.insert(mNew).map(_ => mNew)
    }
  } yield m

  /**
    * Update a Mark with a MarkRef rather than a "real" mark with a MarkData.  A MarkRef just refers to another
    * mark, one that has been shared with the user creating the MarkRef.
    *
    * Given that a rating and labels are technically supposed to apply to the subject/URL we could maybe just do
    * away with this idea of a MarkRef and just create a real mark for the shared-to, non-owner user with the same
    * subject/URL and the non-owner user's data.  This presents 2 problems:
    *   1) A non-owner might think he is rating the owner's mark (and it's data), not the subject/URL of the owner's
    *      mark.
    *   2) A non-owner might want to create his own mark with the same subject/URL.
    */
  def updateMarkRef(user: UUID, referenced: Mark, mdata: MarkData): Future[Mark] = for {
    c <- dbColl()
    mOld <- findOrCreateMarkRef(user, referenced.id)

    // TODO: throw an exception if this non-owner user has attempted to change anything but the rating or (add) labels

    // only update rating if mdata.rating.isDefined, o/w update labels
    refOld = mOld.markRef.get
    refNew = if (mdata.rating.isDefined) refOld.copy(rating = mdata.rating) else {
      // this set diff allows for removal of the SHARED_WITH_ME_TAG
      val netLabels = mdata.tags.getOrElse(Set.empty[String]) diff referenced.mark.tags.getOrElse(Set.empty[String])
      refOld.copy(tags = if (netLabels.isEmpty) None else Some(netLabels))
    }

    now: Long = TIME_NOW

    // if no change to MarkRef then there's nothing to do
    m <- if (refOld == refNew) Future.successful(mOld) else for {

      // the MarkRef's user will be that of the non-owner-user, not the owner-user of the mark
      wr <- c.update(refSel(user, referenced.id), d :~ "$set" -> (d :~ TIMETHRU -> now))
      _ <- wr.failIfError

      // even if refNew doesn't contain any tags or a rating, still execute the following code, the alternative
      // logic doesn't seem worth the complexity
      mNew = mOld.copy(markRef = Some(refNew), timeFrom = now)

      wr <- c.insert(mNew)
      _ <- wr.failIfError
    } yield mNew
  } yield m + refNew

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
        logger.error(msg)
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
  def merge(oldMark: Mark, newMark: Mark, now: Long = TIME_NOW): Future[Mark] = for {
    c <- dbColl()

    // delete the newer mark and merge it into the older/pre-existing one (will return 0 if newMark not in db yet)
    _ <- delete(newMark.userId, Seq(newMark.id), now = now, mergeId = Some(oldMark.id), ensureDeletion = false)
    mergedMk = oldMark.merge(newMark).copy(timeFrom = now, timeThru = INF_TIME)

    // don't do anything if there wasn't a meaningful change to the old mark
    _ <- if (oldMark equalsIgnoreTimeStamps mergedMk) Future.successful(oldMark) else for {

      wr <- c.update(d :~ USR -> mergedMk.userId :~ ID -> mergedMk.id :~ curnt,
                     d :~ "$set" -> (d :~ TIMETHRU -> now))
      _ <- wr.failIfError

      wr <- c.insert(mergedMk)
      _ <- wr.failIfError

    } yield ()
  } yield mergedMk

  /** Process the file into a Page instance and add it to the Mark in the database. */
  def addFilePage(userId: UUID, markId: String, file: TemporaryFile): Future[Unit] = {
    val page = Page(Files.readAllBytes(file))
    addPageSource(userId, markId, page)
  }

  /** Adds web page source to a mark--for "private" reprs of marks saved from the Chrome Extension. */
  def addPageSource(user: UUID, id: String, page: Page, ensureNoPrivRepr: Boolean = true): Future[Unit] = for {
    c <- dbColl()
    sel0 = d :~ USR -> user :~ ID -> id :~ curnt
    sel1 = if (ensureNoPrivRepr) sel0 :~ PRVREPR -> (d :~ "$exists" -> false) else sel0
    wr <- c.findAndUpdate(sel1, d :~ "$set" -> (d :~ PAGE -> page) :~ "$unset" -> (d :~ PGPENDx -> 1))

    _ <- if (wr.lastError.exists(_.n == 1)) Future.unit else {
      val msg = s"Unable to findAndUpdate mark $id's page source; ensureNoPrivRepr = $ensureNoPrivRepr, wr.lastError = ${wr.lastError.get}"
      logger.error(msg)
      Future.failed(new NoSuchElementException(msg))
    }

    m = wr.result[Mark].get
    _ = if (m.privRepr.isDefined) logger.warn(s"Adding page source for mark ${m.id} (${m.timeFrom}) that already has a private representation ${m.privRepr.get}, which will eventually be overwritten")
  } yield ()

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
    wr <- c.update(sel, d :~ "$push" -> (d :~ (if (foreground) TABVISx else TABBGx) -> time))
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
      wr <- c.update(d :~ USR -> thatUser, d :~ "$set" -> (d :~ USR -> thisUser), multi = true)
      _ <- wr.failIfError
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
      // selecting with `USR -> user` is important here for enforcing permissions
      selM = d :~ USR -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
      selR = d :~ USR -> user :~ REFIDx -> (d :~ "$in" -> ids) :~ curnt
      mrg = mergeId.map(d :~ MERGEID -> _).getOrElse(d)
      wr <- c.update(d :~ "$or" -> Seq(selM, selR), d :~ "$set" -> (d :~ TIMETHRU -> now :~ mrg), multi = true)
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
      wr <- c.update(sel, d :~ "$push" -> (d :~ TAGSx -> (d :~ "$each" -> tags)), multi = true)
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
    *   db.entries.find({$or:[{timeThru:NumberLong("9223372036854775807"), pubRepr:{$exists:0}},
    *                         {timeThru:NumberLong("9223372036854775807"), privRepr:{$exists:0}, page:{$exists:1}}]}).explain()
    */
  def findMissingReprs(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing representations")
    for {
      c <- dbColl()

      // selPub and selPriv must be consistent with Mark.representablePublic/Private
      selPub = d :~ curnt :~ PUBREPR -> (d :~ "$exists" -> false) :~
                             PGPENDx -> (d :~ "$ne" -> true) // https://stackoverflow.com/questions/22290538/select-mongodb-documents-where-a-field-either-does-not-exist-is-null-or-is-fal

      // we might leave a Page attached to a mark, if for example the processing of that page fails
      // (see repr-engine's MongoClient.receive in the FailedProcessing case)
      selPriv = d :~ curnt :~ PRVREPR -> (d :~ "$exists" -> false) :~
                              PAGE -> (d :~ "$exists" -> true)

      // looks if there is a record for representation made of user generated content such as
      // comment, highlights, notes, labels (mark tags)
      selUserData = d :~ curnt :~ USRREPR -> (d :~ "$exists" -> false)

      // `curnt` must be part of selPub & selPriv, rather than appearing once outside the $or, to utilize the indexes
      sel = d :~ "$or" -> Seq(selPub, selPriv,  selUserData) // Seq gets automatically converted to BSONArray
      //_ = logger.info(BSONDocument.pretty(sel))
      seq <- c.find(sel).coll[Mark, Seq](n)
    } yield {
      logger.debug(s"${seq.size} marks with missing representations were retrieved")
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
      sel = d :~ "$or" ->
        Seq(d :~ curnt :~  PUBESTARS -> (d :~ "$exists" -> false) :~ PUBREPR -> (d :~ "$exists" -> true),
            d :~ curnt :~ PRIVESTARS -> (d :~ "$exists" -> false) :~ PRVREPR -> (d :~ "$exists" -> true))
      //_ = logger.info(BSONDocument.pretty(sel))
      seq <- c.find(sel).coll[Mark, Seq](n)
    } yield {
      logger.debug(s"${seq.size} marks with missing E[rating]s were retrieved")
      seq
    }
  }

  def fkSel(id: String, timeFrom: Long): BSONDocument = d :~ ID -> id :~ TIMEFROM -> timeFrom

  /**
    * Updates a mark's state with provided foreign key ID.  This method is typically called as a result of
    * findMissingReprs or findMissingExpectedRatings, so if they are picking up non-`curnt` marks, then this method
    * needs to be also, o/w repr-engine's MongoClient.refresh could get stuck hopelessly trying to re-process
    * the same non-current marks over and over.
    *
    * @param user      - mark's user ID; serves as a safeguard against inadvertent private content mixups
    * @param id        - mark ID
    * @param timeFrom  - mark timestamp
    * @param fkId      - "foreign key" ID; probably either a representation ID or an expected rating ID
    * @param fieldName - the field name in the Mark model to update
    * @param logName   - the field name for logging purposes
    */
  def updateForeignKeyId(user: UUID, id: String, timeFrom: Long, fkId: String, fieldName: String, logName: String):
                                                                                                      Future[Mark] = {
    logger.debug(s"Updating mark $id ($timeFrom) with $logName ID: '$fkId'")
    if (fkId.endsWith("Repr") && fkId.length > Representation.ID_LENGTH) // TODO: remove this after updating indexes
      Future.failed(new Exception(s"Attempt to update mark $id ($timeFrom) with $logName ID '$fkId' failed; long ID length could break index"))

    else for {
      c <- dbColl()

      // writes new foreign key ID into the mark and retrieves updated document in the result
      wr <- c.findAndUpdate(fkSel(id, timeFrom), d :~ "$set" -> (d :~ fieldName -> fkId), fetchNewObject = true)

      _ <- if (wr.lastError.exists(_.n == 1)) Future.successful {} else {
        logger.warn(s"Unable to findAndUpdate $logName of mark $id [$timeFrom] to $fkId; wr.lastError = ${wr.lastError.get}")
        Future.failed(new NoSuchElementException(s"Unable to find mark $id [$timeFrom] in order to update its $fieldName"))
      }

      // this will "NoSuchElementException: None.get" when `get` is called if `wr.result[Mark]` is None
      mk = wr.result[Mark].get

      _ = logger.debug(s"Updated mark $id with $logName ID: '$fkId'")
    } yield mk
  }

  /** Updates a mark state with provided expected rating ID. */
  def updatePublicERatingId(user: UUID, id: String, timeFrom: Long, erId: String): Future[Unit] =
    updateForeignKeyId(user, id, timeFrom, erId, PUBESTARS, "public expected rating").map(_ => {})

  /** Updates a mark state with provided private expected rating ID. */
  def updatePrivateERatingId(user: UUID, id: String, timeFrom: Long, erId: String): Future[Unit] =
    updateForeignKeyId(user, id, timeFrom, erId, PRIVESTARS, "private expected rating").map(_ => {})

  /** Updates a mark state with provided representation ID. */
  def updatePublicReprId(user: UUID, id: String, timeFrom: Long, reprId: String): Future[Unit] =
    updateForeignKeyId(user, id, timeFrom, reprId, PUBREPR, "public representation").map(_ => {})

  /**
    * Updates a mark state with provided representation ID for user generated content such as
    * comment, highlights, notes, labels (mark tags).
    */
  def updateUserContentReprId(user: UUID, id: String, timeFrom: Long, reprId: String): Future[Unit] =
    updateForeignKeyId(user, id, timeFrom, reprId, USRREPR, "user content representation").map(_ => {})

  /** Remove a userRepr from a Mark.  Used by MongoAnnotationDao when annotations are created and destroyed. */
  def unsetUserContentReprId(user: UUID, id: String): Future[Unit] = for {
    c <- dbColl()
    sel = d :~ USR -> user :~ ID -> id :~ curnt
    wr <- c.update(sel, d :~ "$unset" -> (d :~ USRREPR -> 1))
    _ <- wr.failIfError
    // TODO: should we delete from the representations collection as well?
  } yield ()

  /**
    * Updates a mark state with provided private representation ID and clears out processed page source.
    * @param page - processed page source to clear out from the mark
    */
  def updatePrivateReprId(user: UUID, id: String, timeFrom: Long, reprId: String, page: Option[Page]): Future[Unit] = {
    logger.debug(s"Updating mark $id ($timeFrom) with private representation ID: '$reprId'")
    if (reprId.length > Representation.ID_LENGTH)
      Future.failed(new Exception(s"Attempt to update mark $id ($timeFrom) with private representation ID '$reprId' failed; long ID length could break index"))

    else for {
      c <- dbColl()
      mk <- updateForeignKeyId(user, id, timeFrom, reprId, PRVREPR, "private representation")

      // removes page source from the mark in case it's the same as the one processed
      _ <- if (page.exists(mk.page.contains)) for {
        wr <- c.update(fkSel(id, timeFrom), d :~ "$unset" -> (d :~ PAGE -> 1))
        _ <- wr.failIfError
      } yield () else Future.successful {}

    } yield
      logger.debug(s"Updated mark $id with private representation ID: '$reprId'")
  }

  /** Returns true if a mark with the given URL was previously deleted.  Used to prevent autosaving in such cases. */
  def isDeleted(user: UUID, url: String): Future[Boolean] = {
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ URLPRFX -> url.binaryPrefix :~ TIMETHRU -> (d :~ "$lt" -> INF_TIME)
      seq <- c.find(sel).coll[Mark, Seq]()
    } yield seq.exists(_.mark.url.contains(url))
  }

  /**
    * Search for a [MarkData] by userId, subject and empty url field for future merge
    * @param userId - user UUID
    * @param subject string subject
    * @return - optional [MarkData]
    */
  def findDuplicateSubject(userId: UUID, subject: String): Future[Option[Mark]] = {
    logger.debug(s"Searching for duplicate subject marks for user $userId and subject '$subject'")
    for {
      c <- dbColl() // TODO: does this query require an index?  or is the "bin-$USR-1-$TIMETHRU-1" index sufficient?
      sel = d :~ USR -> userId :~ SUBJx -> subject :~ URLx -> (d :~ "$exists" -> false) :~ curnt
      opt <- c.find(sel).one[Mark]
    } yield {
      logger.debug(s"Searching for duplicate subject marks finished with result mark ${opt.map(_.id)}")
      opt
    }
  }
}
