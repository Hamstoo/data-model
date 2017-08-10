package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.{Mark, MarkData}
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoMarksDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, ExtendedString, ExtendedWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "entries")
  private val d = BSONDocument.empty
  private val curnt: Producer[BSONElement] = THRU -> Long.MaxValue

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USER -> Ascending :: Nil) % s"bin-$USER-1" ::
      Index(THRU -> Ascending :: Nil) % s"bin-$THRU-1" ::
      /* Following two indexes are set to unique to prevent messing up timeline of entry states. */
      Index(ID -> Ascending :: MILS -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$MILS-1-uniq" ::
      Index(ID -> Ascending :: THRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$THRU-1-uniq" ::
      Index(UPRFX -> Ascending :: REPRS -> Ascending :: Nil) % s"bin-$UPRFX-1-$REPRS-1" ::
      Index(s"$MARK.$SUBJ" -> Text :: s"$MARK.$TAGS" -> Text :: s"$MARK.$COMNT" -> Text :: Nil) %
        s"txt-$MARK.$SUBJ-$MARK.$TAGS-$MARK.$COMNT" ::
      Index(s"$MARK.$TAGS" -> Ascending :: Nil) % s"bin-$MARK.$TAGS-1" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /** Saves a mark to the storage. */
  def create(mark: Mark): Future[Unit] = for {
    c <- futCol
    wr <- c insert mark
    _ <- wr failIfError
  } yield ()

  /** Retrieves a current mark by user and id, None if not found. */
  def receive(user: UUID, id: String): Future[Option[Mark]] = for {
    c <- futCol
    optEnt <- (c find d :~ USER -> user :~ ID -> id :~ curnt).one[Mark]
  } yield optEnt

  /** Retrieves all current marks for the user, sorted by 'from' time. */
  def receive(user: UUID): Future[Seq[Mark]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user :~ curnt sort d :~ MILS -> -1).coll[Mark, Seq]()
  } yield seq

  /** Retrieves a current mark by user and url, None if not found. */
  def receive(url: String, user: UUID): Future[Option[Mark]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user :~ UPRFX -> url.prefx :~ curnt).coll[Mark, Seq]()
  } yield seq collectFirst { case e if e.mark.url contains url => e }

  /** Retrieves all current marks for the user, constrained by a list of tags. Mark must have all tags to qualify. */
  def receiveTagged(user: UUID, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futCol
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags) :~ curnt
    seq <- (c find sel sort d :~ MILS -> -1).coll[Mark, Seq]()
  } yield seq

  /**
    * Retrieves all current marks with representations for the user, constrained by a list of tags. Mark must have
    * all tags to qualify.
    */
  def receiveRepred(user: UUID, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futCol
    sel0 = d :~ USER -> user :~ REPRS -> (d :~ "$exists" -> true :~ "$ne" -> "") :~ curnt
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    seq <- (c find sel1).coll[Mark, Seq]()
  } yield seq

  /** Retrieves all tags existing in current marks for the user. */
  def receiveTags(user: UUID): Future[Set[String]] = for {
    c <- futCol
    sel = d :~ USER -> user :~ curnt
    set <- (c find sel projection d :~ s"$MARK.$TAGS" -> 1 :~ "_id" -> -1).coll[BSONDocument, Set]()
  } yield for {
    d <- set
    ts <- d.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGS) getOrElse Set.empty
  } yield ts

  // TODO: receiveCorrelated -- given a vecrepr find (extremely) highly correlated others (i.e. same URL/content)

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Mark state must be
    * current and have all tags to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futCol
    sel0 = d :~ USER -> user :~ curnt
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    pjn = d :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel1 :~ "$text" -> (d :~ "$search" -> query) projection pjn sort pjn).coll[Mark, Seq]()
  } yield seq

  /**
    * Updates current state of a mark with provided MarkData, looking up the mark up by user and id.
    * Returns new current mark state.
    */
  def update(user: UUID, id: String, mdata: MarkData): Future[Mark] = for {
    c <- futCol
    now = DateTime.now.getMillis
    sel = d :~ USER -> user :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ THRU -> now), fetchNewObject = true)
    mark = wr.result[Mark].get.copy(mark = mdata, repIds = None, timeFrom = now, timeThru = Long.MaxValue)
    wr <- c insert mark
    _ <- wr failIfError
  } yield mark

  /** Appends provided string to mark's array of page sources. */
  def addPageSource(user: UUID, id: String, source: String): Future[Unit] = for {
    c <- futCol
    wr <- c update(d :~ USER -> user :~ ID -> id :~ curnt, d :~ "$push" -> (d :~ PAGE -> source))
    _ <- wr failIfError
  } yield ()

  /**
    * Renames one tag in all user's marks that have it.
    * Returns updated mark states number.
    */
  def updateTag(user: UUID, tag: String, rename: String): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$set" -> (d :~ s"$MARK.$TAGS.$$" -> rename), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Appends `time` to either `.tabVisible` or `.tabBground` array of a mark. */
  def addTiming(user: UUID, id: String, time: RangeMils, foreground: Boolean): Future[Unit] = for {
    c <- futCol
    sel = d :~ USER -> user :~ ID -> id :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ s"$AUX.${if (foreground) TABVIS else TABBG}" -> time))
    _ <- wr failIfError
  } yield ()

  /**
    * Updates all user's marks with new user id, effectively moving them to another user.
    * Returns the number of mark states moved.
    */
  def move(thisUser: UUID, thatUser: UUID): Future[Int] = for {
    c <- futCol
    wr <- c update(d :~ USER -> thatUser, d :~ "$set" -> (d :~ USER -> thisUser), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Updates a set of current marks selected by user and a list of ids to 'thru' time of execution. */
  def delete(user: UUID, ids: Seq[String]): Future[Int] = for {
    c <- futCol
    now = DateTime.now.getMillis
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$set" -> (d :~ THRU -> now), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a tag from all user's marks that have it. */
  def deleteTag(user: UUID, tag: String): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> tag), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Adds a set of tags to each current mark from a list of ids. */
  def tag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$each" -> tags)), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a set of tags from each current mark from a list of ids if they have any of the tags. */
  def untag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$in" -> tags)), multi = true)
  } yield wr.nModified

  /** Retrieves a map of n mark IDs to URLs that belong to marks without representations. */
  def findMissingReprs(n: Int): Future[Map[String, String]] = for {
    c <- futCol
    sel = d :~ UPRFX -> (d :~ "$exists" -> true) :~ UPRFX -> (d :~ "$ne" -> "".getBytes) :~
      REPRS -> (d :~ "$exists" -> false)
    seq <- (c find sel projection d :~ ID -> 1 :~ s"$MARK.$URL" -> 1).coll[BSONDocument, Seq](n)
  } yield seq.map {
    d => d.getAs[String](ID).get -> d.getAs[BSONDocument](MARK).get.getAs[String](URL).get
  }(collection.breakOut[Seq[BSONDocument], (String, String), Map[String, String]])

  /** Updates marks from a list ids with provided representation id. */
  def updateMarkReprId(ids: Set[String], repr: String): Future[Int] = for {
    c <- futCol
    /* When update mark repId if repId exists in perIds array then it should be removed from array
    and be pushed to the end of array to become last. See def receiveRepred : ...  (d :~ "$last" -> REPRS) */
    sel = d :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$pull" -> (d :~ REPRS -> repr), multi = true)
    _ <- wr failIfError;
    wr <- c update(sel, d :~ "$push" -> (d :~ REPRS -> repr), multi = true)
    _ <- wr failIfError
  } yield wr.nModified
}
