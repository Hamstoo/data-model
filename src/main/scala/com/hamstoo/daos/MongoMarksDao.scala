package com.hamstoo.daos

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import com.hamstoo.models.Mark._
import com.hamstoo.models.MarkData.SHARED_WITH_ME_TAG
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.Shareable.{N_SHARED_FROM, N_SHARED_TO, SHARED_WITH}
import com.hamstoo.models._
import com.mohiva.play.silhouette.api.exceptions.NotAuthorizedException
import play.api.Logger
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object MongoMarksDao {
  var migrateData: Boolean = scala.util.Properties.envOrNone("MIGRATE_DATA").exists(_.toBoolean)
}

/**
  * Data access object for MongoDB `entries` (o/w known as "marks") collection.
  */
class MongoMarksDao(db: () => Future[DefaultDB])
                   (implicit userDao: MongoUserDao,
                    urlDuplicatesDao: MongoUrlDuplicatesDao,
                    ex: ExecutionContext) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  val collName: String = "entries"
  private val dbColl: () => Future[BSONCollection] = () => db().map(_ collection collName)
  private def reprsColl(): Future[BSONCollection] = db().map(_ collection "representations")
  private def pagesColl(): Future[BSONCollection] = db().map(_ collection "pages")

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: Nil) % s"bin-$USR-1-$TIMETHRU-1" ::
    // the following two indexes are set to unique to prevent messing up timelines of mark/entry states
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    // text index (there can be only one per collection)
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: SUBJx -> Text :: TAGSx -> Text :: COMNTx -> Text :: Nil) %
      s"bin-$USR-1-$TIMETHRU-1--txt-$SUBJx-$TAGSx-$COMNTx" ::
    Index(TAGSx -> Ascending :: Nil) % s"bin-$TAGSx-1" ::
    Index(USR -> Ascending :: REFIDx -> Ascending :: TIMETHRU -> Ascending :: Nil) % // can't be unique b/c of nulls
      s"bin-$USR-1-$REFIDx-1-$TIMETHRU-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 389 seconds)

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
    * @param timeFrom  A version of a mark is permanently identifiable by its timeFrom, not its timeThru, which
    *                  can change.  If this parameter is None, then the current version of the mark will be returned.
    * @return          None if no such mark is found.
    */
  def retrieveInsecure(id: ObjectId, timeFrom: Option[TimeStamp] = None): Future[Option[Mark]] =
    retrieveInsecureSeq(id :: Nil, timeFrom = timeFrom).map(_.headOption)

  /** Retrieves a list of marks by IDs, ignoring user authorization permissions. */
  def retrieveInsecureSeq(ids: Seq[ObjectId], timeFrom: Option[TimeStamp] = None,
                          begin: Option[TimeStamp] = None, end: Option[TimeStamp] = None): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving (insecure) ${ids.size} marks (timeFrom=${timeFrom.map(_.tfmt)}, begin=${begin.map(_.tfmt)}, end=${end.map(_.tfmt)}); first, at most, 5: ${ids.take(5)}")
    for {
      c <- dbColl()
      sel = d :~ ID -> (d :~ "$in" -> ids) :~
                 timeFrom.fold(curnt)(d :~ TIMEFROM -> _) :~ // if timeFrom is None, look for INF_TIME timeThru
                 begin.fold(d)(ts => d :~ TIMEFROM -> (d :~ "$gte" -> ts)) :~
                 end  .fold(d)(ts => d :~ TIMEFROM -> (d :~ "$lt"  -> ts))

      seq <- c.find(d :~ sel).coll[Mark, Seq]()
    } yield {
      logger.debug(s"Retrieved (insecure) ${seq.size} marks; first, at most, 5: ${seq.take(5).map(_.id)}")
      seq
    }
  }

  /** Retrieves a mark by user and ID, None if not found or not authorized. */
  def retrieve(user: Option[User], id: ObjectId, timeFrom: Option[TimeStamp] = None): Future[Option[Mark]] = {
    logger.debug(s"Retrieving mark $id for user ${user.map(_.usernameId)}")
    for {
      mInsecure <- retrieveInsecure(id, timeFrom = timeFrom)
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

  /** Retrieves all versions of a mark, current and previous, sorted by timeFrom, descending. */
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

  /** Retrieves the original creation time of a mark. */
  def retrieveCreationTime(id: String): Future[Option[TimeStamp]] =
    retrieveInsecureHist(id).map(_.lastOption.map(_.timeFrom))

  /**
    * Retrieves a current mark by user and URL, None if not found.  This is used in the Chrome extension via the
    * backend's `MarksController` to quickly get the mark for an active tab.
    *
    * TODO: Eventually we'll probably want to implement more complex logic based on representations similar to
    * repr-engine's `dupSearch`.  To do this, we could process a (temporary--not stored in DB) representation for every
    * new browser tab, and if it is deemed to be a duplicate of an existing mark, then show star as orange and display
    * highlights/notes.
    */
  def retrieveByUrl(url: String, user: UUID): Future[Option[Mark]] = {
    logger.debug(s"Retrieving marks by URL $url and user $user")
    for {
      // find set of URLs that contain duplicate content to the one requested
      setDups <- urlDuplicatesDao.retrieve(user, url)
      urls = Set(url).union(setDups.flatMap(_.dups))

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

  /** Retrieves all current marks for the user, constrained by a list of tags. Mark must have all tags to qualify. */
  def retrieveTagged(user: UUID, labels: Set[String]): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving marks for user $user and labels $labels")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ TAGSx -> (d :~ "$all" -> labels) :~ curnt
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
  def retrieveRepred(user: UUID, tags: Set[String] = Set.empty[String],
                     begin: Option[TimeStamp] = None, end: Option[TimeStamp] = None): Future[Seq[MSearchable]] = {
    logger.debug(s"Retrieving represented marks for user $user and tags $tags")
    for {
      c <- dbColl()

      // TODO: 146: we need an index for this query (or defer to issue #260)?
      // TODO: FFA: I think it must be defer to issue #260, otherwise how this index must looks like?

      // maybe we should $and instead of $or
      sel = d :~ USR -> user :~ curnt :~ // TODO: should `curnt` be moved into `reprs` to utilize indexes?
                 REPRS -> (d :~ "$not" -> (d :~ "$size" -> 0)) :~
                 (if (tags.isEmpty) d else d :~ TAGSx -> (d :~ "$all" -> tags)) :~
                 begin.fold(d)(ts => d :~ TIMEFROM -> (d :~ "$gte" -> ts)) :~
                 end  .fold(d)(ts => d :~ TIMEFROM -> (d :~ "$lt"  -> ts))

      seq <- c.find(sel).coll[MSearchable, Seq]()
    } yield {
      logger.debug(s"${seq.size} represented marks were successfully retrieved")
      seq.map { m => m.xcopy(aux = m.aux.map(_.cleanRanges)) }
    }
  }

  /**
    * Retrieves all of a user's MarkRefs--i.e. marks owned by other users that have been shared with this one.
    * Returns them in a map from the referenced mark IDs to the MarkRefs themselves--the assumption being they'll
    * need to be "application-level joined" by the caller.
    */
  def retrieveRefed(user: UUID, begin: Option[TimeStamp] = None, end: Option[TimeStamp] = None):
                                                                            Future[Map[ObjectId, MarkRef]] = {
    logger.debug(s"Retrieving referenced marks for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ REFIDx -> (d :~ "$exists" -> true) :~ curnt :~
                 begin.fold(d)(ts => d :~ TIMEFROM -> (d :~ "$gte" -> ts)) :~
                 end  .fold(d)(ts => d :~ TIMEFROM -> (d :~ "$lt"  -> ts))
      seq <- c.find(sel).coll[MSearchable, Seq]()
    } yield {
      logger.debug(s"${seq.size} referenced marks were successfully retrieved")
      seq//.map { m => m.copy(aux = m.aux.map(_.cleanRanges)) } // no longer returning Marks, so no need to cleanRanges
        .map(m => m.markRef.get.markId -> m.markRef.get).toMap
    }
  }

  /** Retrieves all tags existing in all current marks for the given user. */
  def retrieveTags(user: UUID): Future[Set[String]] = {
    logger.debug(s"Retrieving labels for user $user")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ curnt
      docs <- c.find(sel, d :~ TAGSx -> 1 :~ "_id" -> 0).coll[BSONDocument, Set]()
    } yield {
      val labels: Set[String] = for {
        doc <- docs // foreach returned document and foreach mark.tags
        label <- doc.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGSx.split(raw"\.")(1)).getOrElse(Set.empty)
      } yield label
      logger.debug(s"Successfully retrieved ${labels.size} labels: $labels")
      labels
    }
  }

  /**
    * Executes a MongoDB Text Index search using text index with sorting in user's marks, constrained by tags.
    * Mark state must be current (i.e. timeThru == INF_TIME) and have all tags to qualify.
    */
  def search(user: UUID, query: String): Future[Set[MSearchable]] = search(Set(user), query)

  /**
    * Perform Text Index search over the marks of more than one user, which is useful for searching referenced marks,
    * and potentially filter for specific mark IDs.
    */
  def search(users: Set[UUID], query: String, ids: Set[ObjectId] = Set.empty[ObjectId],
             begin: Option[TimeStamp] = None, end: Option[TimeStamp] = None):
                                                                        Future[Set[MSearchable]] = {

    val which = if (users.nonEmpty) s"for ${users.size} users (first, at most, 5: ${users.take(5)}) with ${ids.size}"
                else s"with ${ids.size} IDs (first, at most, 5: ${ids.take(5)})"
    logger.debug(s"Searching for marks $which by text query '$query' between ${begin.map(_.tfmt)} and ${end.map(_.tfmt)}")

    // this projection doesn't have any effect without this selection
    val searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
    val searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

    // it appears that `$in` is not an "equality match condition" as mentioned in the MongoDB Text Index
    // documentation, using it here (rather than Future.sequence) generates the following database error:
    // "planner returned error: failed to use text index to satisfy $text query (if text index is compound,
    // are equality predicates given for all prefix fields?)"
    //val sel = d :~ USR -> (d :~ "$in" -> users) :~ curnt

    // be sure to call dbColl() separately for each element of the following sequence to ensure asynchronous execution
    Future.sequence {
      users.map { u =>

        val sel = d :~ USR -> u :~ curnt :~
                       begin.fold(d)(ts => d :~ TIMEFROM -> (d :~ "$gte" -> ts)) :~
                       end  .fold(d)(ts => d :~ TIMEFROM -> (d :~ "$lt"  -> ts)) :~
                       searchScoreSelection :~
                       (if (ids.isEmpty) d else d :~ ID -> (d :~ "$in" -> ids))

        dbColl().flatMap(_.find(sel, searchScoreProjection).coll[MSearchable, Seq]())
      }
    }.map(_.flatten).map { set =>
      logger.debug(s"Search retrieved ${set.size} marks")
      set.map { m => m.xcopy(aux = m.aux.map(_.cleanRanges)) }
    }
  }

  /** Akka Stream */
  def stream(userId: UUID, begin: TimeStamp, end: TimeStamp)(implicit m: Materializer): Source[Mark, NotUsed] = {
    logger.debug(s"Streaming user $userId's marks between ${begin.dt} and ${end.dt}")

    // TODO: issue #146, loop through all of each mark's reprs/versions and timestamps
    Source.fromFuture(dbColl())
      .flatMapConcat { c =>
        import reactivemongo.akkastream.cursorProducer
        val btw = d :~ TIMEFROM -> (d :~ "$gte" -> begin :~ "$lt" -> end)
        c.find(d :~ USR -> userId :~ curnt :~ btw).sort(d :~ TIMEFROM -> 1).cursor[Mark]().documentSource()
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
        throw new NotAuthorizedException(s"User ${user.map(_.usernameId)} unauthorized to view mark $id")
      case Some(m) =>

        // update a MarkRef if there's a logged in, non-owner user who just wants to add labels or a rating, if the
        // non-owner is authorized for writing then a change to the set of labels is reflected on the actual mark,
        // but if not then they additional labels will be put on the MarkRef (and only viewable to that non-owner user)
        val updateRef = user.exists(!m.ownedBy(_)) && (!authorizedWrite || mdata.rating.isDefined)

        if (!authorizedWrite && !updateRef)
          throw new NotAuthorizedException(s"User ${user.map(_.usernameId)} unauthorized to modify mark $id")
        (m, updateRef)
    }

    now: TimeStamp = TIME_NOW

    // if updateRef is true then just update a mark with a MarkRef, o/w update the actual mark (with the MarkData)
    m <- if (updateRef) updateMarkRef(user.get.id, mOld, mdata) else {

      // if `mdata` arrives without a rating then that indicates the mark was saved not by clicking stars so we need
      // to populate it with the existing value (see SingleMarkController.updateMark in frontend and updateMarkRef also)
      val populatedRating = mdata.rating
        .fold(mdata.copy(rating = mOld.mark.rating))(_ => mdata)
        .copy(tags = mdata.tags.map(_ - SHARED_WITH_ME_TAG)) // never put SHARED_WITH_ME_TAG on a real non-MarkRef mark

      if (mOld.mark == populatedRating) Future.successful(mOld) else for {

        // be sure to not use `user`, which could be different from `mOld.userId` if the the mark has been shared
        wr <- c.update(d :~ USR -> mOld.userId :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now))
        _ <- wr.failIfError

        mNew = mOld.copy(mark = populatedRating, timeFrom = now, timeThru = INF_TIME,
                         modifiedBy = user.map(_.id)).removeStaleReprs(mOld)
        wr <- c.insert(mNew)
        _ <- wr.failIfError

        // only create a MarkRef in the database if the user is non-None, o/w there'd be nowhere to put it
        ref <- if (user.exists(!mNew.ownedBy(_))) findOrCreateMarkRef(user.get.id, mNew.id).map(Some(_))
               else Future.successful(None)

      } yield mNew.mask(ref.flatMap(_.markRef), user) // might be a no-op if user owns the mark
    }
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
      val ref = MarkRef(refId, tags = Some(Set(SHARED_WITH_ME_TAG))) // user can remove this tag later

      // for now we'll set pubRepr/userRepr/pubExpRating to prevent repr-engine from doing the same, but
      // once issue #260 is implemented we will merely not send a message to repr-engine to process this mark
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

    now: TimeStamp = TIME_NOW

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
  } yield referenced.mask(Some(refNew), None)

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
  def merge(oldMark: Mark, newMark: Mark, now: Long = TIME_NOW): Future[Mark] = {
    logger.debug(s"Merge marks (oldOne: $oldMark.id and newOne: $newMark.id")

    for {
      c <- dbColl()

      // delete the newer mark and merge it into the older/pre-existing one (will return 0 if newMark not in db yet)
      _ <- delete(newMark.userId, Seq(newMark.id), now = now, mergeId = Some(oldMark.id), ensureDeletion = false)

      mergedMk = oldMark.merge(newMark).copy(timeFrom = now, timeThru = INF_TIME).removeStaleReprs(oldMark)

      // don't do anything if there wasn't a meaningful change to the old mark
      _ <- if (oldMark equalsIgnoreTimeStamps mergedMk) Future.unit else for {

        wr <- c.update(d :~ USR -> mergedMk.userId :~ ID -> mergedMk.id :~ curnt,
                       d :~ "$set" -> (d :~ TIMETHRU -> now))
        _ <- wr.failIfError

        wr <- c.insert(mergedMk)
        _ <- wr.failIfError

      } yield ()
    } yield {
      logger.debug(s"Marks $oldMark.id and $newMark.id were successfully merged")
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
        val msg = s"Unable to delete marks; ${wr.nModified} out of ${ids.size} were successfully deleted; first attempted, at most, 5: ${ids.take(5)}"
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
   * Save a ReprInfo to a mark's `reprs` list.
   *
   * PUBLIC and USER_CONTENT type reprs are singletons, per mark, so they are updated and replaced
   * if they already exist.  PRIVATE type reprs, on the other hand, are not, so there can be
   * multiple of them per mark, so just insert any new one that comes along.
   */
  def insertReprInfo(markId: ObjectId, reprInfo: ReprInfo): Future[Unit] = {

    /** Insert representation info */
    def insertRepr(markId: ObjectId, reprInfo: ReprInfo): Future[Unit] = for {
      c <- dbColl()
      _ = logger.debug(s"Inserting ${reprInfo.reprType} representation ${reprInfo.reprId} for mark $markId")
      sel = d :~ ID -> markId :~ curnt
      mod = d :~ "$push" -> (d :~ REPRS -> reprInfo)

      wr <- c.update(sel, mod)
      _ <- wr.failIfError
    } yield logger.debug(s"Inserted ${reprInfo.reprType} representation ${reprInfo.reprId} for mark $markId")

    /** Update non-private representation */
    def updateNonPrivateRepr(markId: ObjectId, reprInfo: ReprInfo): Future[Unit] = for {
      c <- dbColl()
      reprType = reprInfo.reprType
      _ = logger.debug(s"Updating $reprType representation ${reprInfo.reprId} for mark $markId")
      sel = d :~ ID -> markId :~ REPR_TYPEx -> reprType :~ curnt
      mod = d :~
        "$set" -> (d :~ REPR_IDxp -> reprInfo.reprId :~ CREATEDxp -> reprInfo.created) :~
        "$unset" -> (d :~ EXP_RATINGxp -> 1)

      wr <- c.update(sel, mod)
      _ <- wr.failIfError
    } yield logger.debug(s"Updated $reprType representation ${reprInfo.reprId}")

    /** Check if non-private representation info exist */
    def nonPrivateReprExists(markId: ObjectId, reprType: String): Future[Boolean] = for {
      c <- dbColl()
      _ = logger.debug(s"Checking for existence of non-private repr of type $reprType")
      sel = d :~ ID -> markId :~  REPR_TYPEx -> reprType.toString :~ curnt
      opt <- c.find(sel).one[Mark]
    } yield {
      logger.debug(s"Retrieved non-private repr $opt")
      opt.nonEmpty
    }

    if (reprInfo.isPrivate) insertRepr(markId, reprInfo)
    else nonPrivateReprExists(markId, reprInfo.reprType) map { exists =>
      if (exists) updateNonPrivateRepr(markId, reprInfo)
      else insertRepr(markId, reprInfo)
    }
  }

  /** Returns true if a mark with the given URL was previously deleted.  Used to prevent autosaving in such cases. */
  def isDeleted(user: UUID, url: String): Future[Boolean] = {
    logger.debug(s"Checking if mark was deleted, for user $user and URL: $url")
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ URLPRFX -> url.binaryPrefix :~ TIMETHRU -> (d :~ "$lt" -> INF_TIME)
      seq <- c.find(sel).coll[Mark, Seq]()
    } yield {
      val deleted = seq.exists(_.mark.url.contains(url))
      logger.debug(s"Mark for user $user and URL $url: isDeleted = $deleted")
      seq.exists(_.mark.url.contains(url))
    }
  }

  /**
    * Search for a [MarkData] by userId, subject and empty url field for future merge
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

  /**
    * Update the expected rating ID of a ReprInfo of a mark given either one of the singleton ReprTypes (PUBLIC or
    * USER_CONTENT) or repr ID (which can correspond to a ReprInfo of any ReprType).
    */
  def updateExpectedRating(m: Mark, reprId: ObjectId, expRatingId: ObjectId): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Updating $reprId expected rating to $expRatingId for mark ${m.id}")
    //reprId <- repr.toReprId(m)(this, implicitly)
    sel = d :~ USR -> m.userId :~ ID -> m.id :~ TIMEFROM -> m.timeFrom :~
               REPRS -> (d :~ "$elemMatch" -> (d :~ REPR_ID -> reprId))
    mod = d :~ "$set" -> (d :~ EXP_RATINGxp -> expRatingId)
    wr <- c.update(sel, mod)
    _ <- wr.failIfError
  } yield logger.debug(s"${wr.nModified} $reprId expected ratings were updated for mark ${m.id}")

  /**
    * Remove a ReprInfo from a mark given either a singleton ReprType or a repr ID.  Used by MongoAnnotationDao
    * when annotations are created and destroyed.
    */
  def unsetRepr(m: Mark, repr: Either[ObjectId, ReprType.Value]): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Removing $repr ReprInfo from mark ${m.id}")
    reprId <- repr.toReprId(m)(this, implicitly)
    sel = d :~ USR -> m.userId :~ ID -> m.id :~ TIMEFROM -> m.timeFrom
    mod = d :~ "$pull" -> (d :~ REPRS -> (d :~ REPR_ID -> reprId))
    wr <- c.update(sel, mod)
    _ <- wr.failIfError
  } yield logger.debug(s"${wr.nModified} ReprInfos $repr were removed from mark ${m.id}")
}
