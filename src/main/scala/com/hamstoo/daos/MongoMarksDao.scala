package com.hamstoo.daos

import java.nio.file.Files
import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.{Mark, MarkData, Page, Representation}
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for MongoDB `entries` (o/w known as "marks") collection.
  */
class MongoMarksDao(db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "entries")

  // reduce size of existing `urlPrfx`s down to URL_PREFIX_LENGTH to prevent indexes below from being too large
  // and causing exceptions when trying to update marks with reprIds (version 0.9.16)
  // (note that the offending `urlPrfx` indexes have now been removed)
  Await.result(for {
    c <- dbColl()
    _ = logger.info(s"Performing data migration for `marks` collection")
    sel = d :~ "$where" -> s"Object.bsonsize({$URLPRFX:this.$URLPRFX})>$URL_PREFIX_LENGTH+19"
    longPfxed <- c.find(sel).coll[Mark, Seq]()
    _ = logger.info(s"Updating ${longPfxed.size} `Mark.urlPrfx`s to length $URL_PREFIX_LENGTH bytes")
    _ <- Future.sequence { longPfxed.map { m => // urlPrfx will have been overwritten upon `Mark` construction
        c.update(d :~ ID -> m.id :~ TIMEFROM -> m.timeFrom, d :~ "$set" -> (d :~ URLPRFX -> m.urlPrfx))
    }}
  } yield (), 373 seconds)

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: TIMETHRU -> Ascending :: Nil) % s"bin-$USR-1-$TIMETHRU-1" ::
    // the following two indexes are set to unique to prevent messing up timelines of mark/entry states
    Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    // findMissingReprs indexes
    Index(PUBREPR -> Ascending :: Nil) % s"bin-$PUBREPR-1" ::
    Index(PRVREPR -> Ascending :: Nil, partialFilter = Some(d :~ PAGE -> (d :~ "$exists" -> true))) %
      s"bin-$PRVREPR-1-partial-$PAGE" ::
    // findMissingExpectedRatings (partial) indexes
    Index(PUBESTARS -> Ascending :: Nil, partialFilter = Some(d :~ PUBREPR -> (d :~ "$exists" -> true))) %
      s"bin-$PUBESTARS-1-partial-$PUBREPR" ::
    Index(PRIVESTARS -> Ascending :: Nil, partialFilter = Some(d :~ PRVREPR -> (d :~ "$exists" -> true))) %
      s"bin-$PRIVESTARS-1-partial-$PRVREPR" ::
    // text index (there can be only one per collection)
    Index(SUBJx -> Text :: TAGSx -> Text :: COMNTx -> Text :: Nil) % s"txt-$SUBJx-$TAGSx-$COMNTx" ::
    Index(TAGSx -> Ascending :: Nil) % s"bin-$TAGSx-1" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager.ensure(indxs)), 389 seconds)

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
      ms = marks map(_.copy(timeFrom = now)) map Mark.entryBsonHandler.write // map each mark into a `BSONDocument`
      wr <- c bulkInsert(ms, ordered = false)
    } yield {
      val count = wr.totalN
      logger.debug(s"$count marks were successfully inserted")
      count
    }
  }

  /** Retrieves a mark by user and ID, None if not found.  Retrieves current mark unless timeThru is specified. */
  def retrieve(user: UUID, id: String, timeThru: Long = INF_TIME): Future[Option[Mark]] = {
    logger.debug(s"Retrieving mark for user $user and ID $id")
    for {
      c <- dbColl()
      optEnt <- c.find(d :~ USR -> user :~ ID -> id :~ TIMETHRU -> timeThru).one[Mark]
    } yield {
      logger.debug(s"$optEnt was successfully retrieved")
      optEnt
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

  /** Retrieves all marks by ID, including previous versions, sorted by `timeFrom` descending. */
  def retrieveAllById(id: String): Future[Seq[Mark]] = {
    logger.debug(s"Retrieving all marks by ID $id")
    for {
      c <- dbColl()
      seq <- c.find(d :~ ID -> id).sort(d :~ TIMEFROM -> -1).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} marks were successfully retrieved by ID")
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
      c <- dbColl()
      seq <- (c find d :~ USR -> user :~ URLPRFX -> url.binaryPrefix :~ curnt).coll[Mark, Seq]()
    } yield {
      val optMark = seq find (_.mark.url.contains(url))
      logger.debug(s"$optMark mark was successfully retrieved")
      optMark
    }
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
    * Retrieves all current marks with representations for the user, constrained by a list of tags. Mark must have
    * all tags to qualify.
    */
  def retrieveRepred(user: UUID, tags: Set[String] = Set.empty[String]): Future[Seq[Mark]] = {
    logger.debug(s"Retrieve repred marks for user $user and tags $tags")
    for {
      c <- dbColl()
      exst = d :~ "$exists" -> true :~ "$ne" -> ""
      sel0 = d :~ USR -> user :~ curnt :~ "$or" -> BSONArray(d :~ PUBREPR -> exst, d :~ PRVREPR -> exst)
      sel1 = if (tags.isEmpty) sel0 else sel0 :~ TAGSx -> (d :~ "$all" -> tags)
      seq <- c.find(sel1, searchExcludedFields).coll[Mark, Seq]()
    } yield {
      logger.debug(s"${seq.size} repred marks were successfully retrieved")
      seq
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
  // TODO: doesn't it make more sense to do an explicit include than an exclude; i.e. just include the required fields
  // TODO: or perhaps it would make more sense to make a MarkStub base class so that users know they're dealing with a partially populated Mark
  val searchExcludedFields: BSONDocument = d :~ (PAGE -> 0)  :~ (URLPRFX -> 0) :~ (AUX -> 0) :~
    (MERGEID -> 0) :~ (TAGSx -> 0) :~ (COMNTx -> 0) :~ (COMNTENCx -> 0)

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Mark state must be
    * current and have all tags to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Mark]] = {
    logger.debug(s"Searching for marks for user $user by text query '$query' and tags $tags")
    for {
      c <- dbColl()
      sel0 = d :~ USR -> user :~ curnt
      sel1 = if (tags.isEmpty) sel0 else sel0 :~ TAGSx -> (d :~ "$all" -> tags)

      // this projection doesn't have any effect without this selection
      searchScoreSelection = d :~ "$text" -> (d :~ "$search" -> query)
      searchScoreProjection = d :~ SCORE -> (d :~ "$meta" -> "textScore")

      seq <- c.find(sel1 :~ searchScoreSelection,
                    searchExcludedFields :~ searchScoreProjection)/*.sort(searchScoreProjection)*/
        .coll[Mark, Seq]()

    } yield {
      logger.debug(s"${seq.size} marks were successfully retrieved")
      seq
    }
  }

  /**
    * Updates current state of a mark with user-provided MarkData, looking the mark up by user and ID.
    * Returns new current mark state.  Do not attempt to use this function to update non-user-provided data
    * fields (i.e. non-MarkData).
    */
  def update(user: UUID, id: String, mdata: MarkData): Future[Mark] = for {
    c <- dbColl()
    sel = d :~ USR -> user :~ ID -> id :~ curnt
    now: Long = TIME_NOW
    wr <- c.findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now))
    oldMk <- wr.result[Mark].map(Future.successful).getOrElse(
      Future.failed(new Exception(s"MongoMarksDao.update: unable to find mark $id")))
    // if the URL has changed then discard the old public repr (only the public one though as the private one is
    // based on private user content that was only available from the browser extension at the time the user first
    // created it)
    pubRp = if (mdata.url.isDefined && mdata.url == oldMk.mark.url ||
                mdata.url.isEmpty && mdata.subj == oldMk.mark.subj) oldMk.pubRepr else None
    newMk = oldMk.copy(mark = mdata, pubRepr = pubRp, timeFrom = now, timeThru = INF_TIME)
    wr <- c.insert(newMk)
    _ <- wr.failIfError
  } yield newMk

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

    // delete the newer mark and merge it into the older/pre-existing one
    _ <- delete(newMark.userId, Seq(newMark.id), now = now, mergeId = Some(oldMark.id))
    mergedMk = oldMark.merge(newMark).copy(timeFrom = now, timeThru = INF_TIME)

    // this was formerly (2017-10-18) a bug as it doesn't affect any of the non-MarkData fields
    //updatedMk <- this.update(mergedMk.userId, mergedMk.id, mergedMk.mark, now = now)

    sel = d :~ USR -> mergedMk.userId :~ ID -> mergedMk.id :~ curnt
    wr <- c.update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now))
    _ <- wr.failIfError

    wr <- c.insert(mergedMk)
    _ <- wr.failIfError

  } yield mergedMk

  /** Process the file into a Page instance and add it to the Mark in the database. */
  def addFilePage(userId: UUID, markId: String, file: TemporaryFile): Future[Unit] = {
    val page = Page(Files.readAllBytes(file))
    addPageSource(userId, markId, page)
  }

  /** Appends provided string to mark's array of page sources. */
  def addPageSource(user: UUID, id: String, page: Page, ensureNoPrivRepr: Boolean = true): Future[Unit] = for {
    c <- dbColl()
    sel0 = d :~ USR -> user :~ ID -> id :~ curnt
    sel1 = if (ensureNoPrivRepr) sel0 :~ PRVREPR -> (d :~ "$exists" -> false) else sel0
    wr <- c.findAndUpdate(sel1, d :~ "$set" -> (d :~ PAGE -> page))

    _ <- if (wr.lastError.exists(_.n == 1)) Future.successful {} else {
      logger.error(s"Unable to findAndUpdate mark $id's page source; ensureNoPrivRepr = $ensureNoPrivRepr, wr.lastError = ${wr.lastError.get}")
      Future.failed(new NoSuchElementException("MongoMarksDao.addPageSource"))
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
      wr <- c update(sel, d :~ "$set" -> (d :~ s"$TAGSx.$$" -> rename), multi = true)
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
             mergeId: Option[String] = None): Future[Int] = {

    logger.debug(s"Deleting marks for user $user: $ids")

    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
      mrg = mergeId.map(d :~ MERGEID -> _).getOrElse(d)
      wr <- c update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now :~ mrg), multi = true)
      _ <- wr failIfError
    } yield {
      val count = wr.nModified
      logger.debug(s"$count were successfully deleted")
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
    *   `db.entries.find({$or:[{pubRepr:{$exists:0}}, {privRepr:{$exists:0}, page:{$exists:1}}]}).explain()`
    */
  def findMissingReprs(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing representations")
    for {
      c <- dbColl()

      // selPub and selPriv must be consistent with Mark.representablePublic/Private
      selPub = d :~ PUBREPR -> (d :~ "$exists" -> false)

      // we might leave a Page attached to a mark, if for example the processing of that page fails
      // (see repr-engine's MongoClient.receive in the FailedProcessing case)
      selPriv = d :~ PRVREPR -> (d :~ "$exists" -> false) :~
                     PAGE -> (d :~ "$exists" -> true)

      sel = d :~ "$or" -> Seq(selPub, selPriv) // Seq gets automatically converted to BSONArray
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
    * db.entries.find({$or:[{pubExpRating:{$exists:0}, pubRepr:{$exists:1}},
    *                       {privExpRating:{$exists:0}, privRepr:{$exists:1}}]}).explain()
    */
  def findMissingExpectedRatings(n: Int): Future[Seq[Mark]] = {
    logger.debug("Finding marks with missing expected ratings")
    for {
      c <- dbColl()
      sel = d :~ "$or" -> Seq(d :~ PUBESTARS -> (d :~ "$exists" -> false) :~ PUBREPR -> (d :~ "$exists" -> true),
                              d :~ PRIVESTARS -> (d :~ "$exists" -> false) :~ PRVREPR -> (d :~ "$exists" -> true))
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

  /** Updates a mark state with provided private representation ID and clears out processed page source.
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

      _ = logger.debug(s"Updated mark $id with private representation ID: '$reprId'")
    } yield ()
  }

  /** Returns true if a mark with the given URL was previously deleted.  Used to prevent autosaving in such cases. */
  def isDeleted(user: UUID, url: String): Future[Boolean] = {
    for {
      c <- dbColl()
      sel = d :~ USR -> user :~ URLPRFX -> url.binaryPrefix :~ TIMETHRU -> (d :~ "$lt" -> INF_TIME)
      seq <- c.find(sel).coll[Mark, Seq]()
    } yield seq.exists(_.mark.url.contains(url))
  }
}
