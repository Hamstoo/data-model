package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.{Mark, MarkData, Page}
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for MongoDB `entries` (o/w known as "marks") collection.
  */
class MongoMarksDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  private val futColl: Future[BSONCollection] = db map (_ collection "entries")

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USER -> Ascending :: Nil) % s"bin-$USER-1" ::
      Index(TIMETHRU -> Ascending :: Nil) % s"bin-$TIMETHRU-1" ::
      /* Following two indexes are set to unique to prevent messing up timeline of entry states. */
      Index(ID -> Ascending :: TIMEFROM -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMEFROM-1-uniq" ::
      Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
      Index(UPRFX -> Ascending :: PUBREPR -> Ascending :: Nil) % s"bin-$UPRFX-1-$PUBREPR-1" ::
      Index(UPRFX -> Ascending :: PRVREPR -> Ascending :: Nil) % s"bin-$UPRFX-1-$PRVREPR-1" ::
      Index(s"$MARK.$SUBJ" -> Text :: s"$MARK.$TAGS" -> Text :: s"$MARK.$COMNT" -> Text :: Nil) %
        s"txt-$MARK.$SUBJ-$MARK.$TAGS-$MARK.$COMNT" ::
      Index(s"$MARK.$TAGS" -> Ascending :: Nil) % s"bin-$MARK.$TAGS-1" ::
      Nil toMap;
  futColl map (_.indexesManager ensure indxs)

  /** Saves a mark to the storage or updates if the user already has a mark with such URL. */
  def insert(mark: Mark): Future[Mark] = for {
    c <- futColl
    now = DateTime.now.getMillis
    wr <- c insert mark
    _ <- wr failIfError;
      _ = logger.debug(s"Inserted mark ${mark.id}")
  } yield mark

  /** Retrieves a mark by user and ID, None if not found.  Retrieves current mark unless timeThru is specified. */
  def retrieve(user: UUID, id: String, timeThru: Long = INF_TIME): Future[Option[Mark]] = for {
    c <- futColl
    optEnt <- (c find d :~ USER -> user :~ ID -> id :~ TIMETHRU -> timeThru).one[Mark]
  } yield optEnt

  /** Retrieves all current marks for the user, sorted by `timeFrom` descending. */
  def retrieve(user: UUID): Future[Seq[Mark]] = for {
    c <- futColl
    seq <- c.find(d :~ USER -> user :~ curnt).sort(d :~ TIMEFROM -> -1).coll[Mark, Seq]()
  } yield seq

  /** Retrieves all marks by ID, including previous versions, sorted by `timeFrom` descending. */
  def retrieveAllById(user: UUID, id: String): Future[Seq[Mark]] = for {
    c <- futColl
    seq <- c.find(d :~ ID -> id).sort(d :~ TIMEFROM -> -1).coll[Mark, Seq]()
  } yield seq

  /**
    * Retrieves a current mark by user and URL, None if not found.  This is used in the Chrome extension via the
    * backend's `MarksController` to quickly get the mark for an active tab.  Eventually we'll probably want to
    * implement more complex logic based on representations similar to repr-engine's `dupSearch`.
    */
  def retrieveByUrl(url: String, user: UUID): Future[Option[Mark]] = for {
    c <- futColl
    seq <- (c find d :~ USER -> user :~ UPRFX -> url.prefx :~ curnt).coll[Mark, Seq]()
  } yield seq collectFirst { case e if e.mark.url contains url => e }

  /** Retrieves all current marks for the user, constrained by a list of tags. Mark must have all tags to qualify. */
  def retrieveTagged(user: UUID, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futColl
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags) :~ curnt
    seq <- (c find sel sort d :~ TIMEFROM -> -1).coll[Mark, Seq]()
  } yield seq

  /**
    * Retrieves all current marks with representations for the user, constrained by a list of tags. Mark must have
    * all tags to qualify.
    */
  def retrieveRepred(user: UUID, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futColl
    exst = d :~ "$exists" -> true :~ "$ne" -> ""
    sel0 = d :~ USER -> user :~ curnt :~ "$or" -> BSONArray(d :~ PUBREPR -> exst, d :~ PRVREPR -> exst)
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    seq <- (c find sel1).coll[Mark, Seq]()
  } yield seq

  /** Retrieves all tags existing in current marks for the user. */
  def retrieveTags(user: UUID): Future[Set[String]] = for {
    c <- futColl
    sel = d :~ USER -> user :~ curnt
    set <- (c find sel projection d :~ s"$MARK.$TAGS" -> 1 :~ "_id" -> 0).coll[BSONDocument, Set]()
  } yield for {
    d <- set
    ts <- d.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGS) getOrElse Set.empty
  } yield ts

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Mark state must be
    * current and have all tags to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futColl
    sel0 = d :~ USER -> user :~ curnt
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    pjn = d :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- c.find(sel1 :~ "$text" -> (d :~ "$search" -> query), pjn).sort(pjn).coll[Mark, Seq]()
  } yield seq

  /**
    * Updates current state of a mark with user-provided MarkData, looking the mark up by user and ID.
    * Returns new current mark state.
    */
  def update1(user: UUID, id: String, mdata: MarkData, now: Long = DateTime.now.getMillis): Future[Mark] = for {
    c <- futColl
    sel = d :~ USER -> user :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    oldMk = wr.result[Mark].get
    // if the URL has changed then discard the old public repr (only the public one though as the private one is
    // based on private user content that was only available from the browser extension at the time the user first
    // created it)
    pubRp = if (mdata.url == oldMk.mark.url) oldMk.pubRepr else None
    newMk = oldMk.copy(mark = mdata, pubRepr = pubRp, timeFrom = now, timeThru = Long.MaxValue)
    wr <- c insert newMk
    _ <- wr failIfError
  } yield newMk

  /**
    * Merge two marks by setting their `timeFrom`s to the time of execution and inserting a new mark with the
    * same `timeThru`.
    */
  def merge(oldMark: Mark, newMark: Mark, now: Long = DateTime.now.getMillis): Future[Mark] = for {
    c <- futColl
    _ <- delete(newMark.userId, Seq(newMark.id), now = now, mergeId = Some(oldMark.id))
    mergedMk = oldMark.merge(newMark)
    updatedMk <- this.update1(mergedMk.userId, mergedMk.id, mergedMk.mark, now = now)
  } yield updatedMk

  /** Appends provided string to mark's array of page sources. */
  def addPageSource(user: UUID, id: String, page: Page): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USER -> user :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ PAGE -> page))
    _ <- wr failIfError
  } yield ()

  /**
    * Renames one tag in all user's marks that have it.
    * Returns updated mark states number.
    */
  def updateTag(user: UUID, tag: String, rename: String): Future[Int] = for {
    c <- futColl
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$set" -> (d :~ s"$MARK.$TAGS.$$" -> rename), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Appends `time` to either `.tabVisible` or `.tabBground` array of a mark. */
  def addTiming(user: UUID, id: String, time: RangeMils, foreground: Boolean): Future[Unit] = for {
    c <- futColl
    sel = d :~ USER -> user :~ ID -> id :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ s"$AUX.${if (foreground) TABVIS else TABBG}" -> time))
    _ <- wr failIfError
  } yield ()

  /**
    * Updates all user's marks with new user id, effectively moving them to another user.
    * Returns the number of mark states moved.
    */
  def move(thisUser: UUID, thatUser: UUID): Future[Int] = for {
    c <- futColl
    wr <- c update(d :~ USER -> thatUser, d :~ "$set" -> (d :~ USER -> thisUser), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Updates `timeThru` of a set of current marks (selected by user and a list of IDs) to time of execution. */
  def delete(user: UUID, ids: Seq[String], now: Long = DateTime.now.getMillis, mergeId: Option[String] = None):
                                                                          Future[Int] = for {
    c <- futColl
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    mrg = mergeId.map(d :~ MERGEID -> _).getOrElse(d)
    wr <- c update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now :~ mrg), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a tag from all user's marks that have it. */
  def deleteTag(user: UUID, tag: String): Future[Int] = for {
    c <- futColl
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> tag), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Adds a set of tags to each current mark from a list of IDs. */
  def tag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futColl
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$each" -> tags)), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a set of tags from each current mark from a list of IDs if they have any of the tags. */
  def untag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futColl
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$in" -> tags)), multi = true)
  } yield wr.nModified

  /** Retrieves a list of n marks that require representations. Intentionally not filtering for `curnt` marks. */
  def findMissingReprs(n: Int): Future[Seq[Mark]] = for {
    c <- futColl
    sel = d :~ UPRFX -> (d :~ "$exists" -> true) :~ UPRFX -> (d :~ "$ne" -> "".getBytes) :~
          // note that this can result in the overwrite of a `privRepr` if both it and `page` exist
      "$or" -> BSONArray(d :~ PUBREPR -> (d :~ "$exists" -> false), d :~ s"$PAGE" -> (d :~ "$exists" -> true))
    seq <- (c find sel).coll[Mark, Seq](n)
  } yield seq

  /**
    * Updates a mark state with provided representation id. this method is typically called as a result
    * of findMissingReprs, so if the latter is picking up non-`curnt` marks, then the former needs to be also, o/w
    * repr-engine's MongoClient.refresh could get stuck hopelessly trying to assign reprs to the same non-current
    * marks over and over.
    */
  def updatePublicReprId(id: String, timeFrom: Long, reprId: String): Future[Unit] = for {
    c <- futColl
    sel = d :~ ID -> id :~ TIMEFROM -> timeFrom
    wr <- c update(sel, d :~ "$set" -> (d :~ PUBREPR -> reprId))
    _ <- wr failIfError;
    _ = logger.debug(s"Updated mark $id with public representation ID $reprId")
  } yield ()

  /**
    * Updates a mark state with provided private representation id and clears out processed page source. this method is
    * typically called as a result of findMissingReprs, so if the latter is picking up non-`curnt` marks, then the
    * former needs to be also, o/w repr-engine's MongoClient.refresh could get stuck hopelessly trying to assign
    * reprs to the same non-current marks over and over.
    *
    * @param user     - user ID of mark's owner; serves as a safeguard against inadvertent private content mixups
    * @param id       - mark ID
    * @param timeFrom - mark version timestamp
    * @param reprId    - representation ID
    * @param page     - processed page source to clear out from the mark
    */
  def updatePrivateReprId(user: UUID, id: String, timeFrom: Long, reprId: String, page: Page): Future[Unit] = for {
    c <- futColl
    sel = d :~ ID -> id :~ TIMEFROM -> timeFrom

    // writes new private representation ID into the mark and retrieves updated document in the result
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ PRVREPR -> reprId), fetchNewObject = true)

    _ <- if (wr.lastError.exists(_.n == 1)) Future.successful {} else {
      logger.warn(s"Unable to findAndUpdate mark $id's (timeFrom = $timeFrom) private representation to $reprId; wr.lastError = ${wr.lastError.get}")
      Future.failed(new Exception("MongoMarksDao.updatePrivateReprId"))
    }

    // this will "NoSuchElementException: None.get" when `get` is called if `wr.result[Mark]` is None
    mk = wr.result[Mark].get

    // removes page source from the mark in case it's the same as the one processed
    _ <- if (mk.page.contains(page)) for {
      wr <- c update(sel, d :~ "$unset" -> (d :~ PAGE -> 1))
      _ <- wr failIfError
    } yield () else Future.successful {}

    _ = logger.debug(s"Updated mark $id with private representation ID $reprId")
  } yield ()
}
