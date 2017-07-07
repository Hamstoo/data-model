package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Entry._
import com.hamstoo.models.Mark._
import com.hamstoo.models.{Entry, Mark}
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoMarksDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, StrWithBinaryPrefix, ExtendedWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "entries")
  private val d = BSONDocument.empty
  private val curnt: Producer[BSONElement] = THRU -> Long.MaxValue

  /* Data migration to 0.7.1 that adds `mark.urlPrfx` field to documents which should have it. */
  for {
    c <- futCol
    sel = d :~ s"$MARK.$URL" -> (d :~ "$exists" -> true) :~ s"$MARK.$UPRFX" -> (d :~ "$exist" -> false)
    n <- c count Some(sel)
    if n > 0
  } for {seq <- (c find sel).coll[Entry, Seq]()} for {e <- seq} c update(d :~ ID -> e.id, e)

  /* Data migration to 0.8.0 that brings in the new fields for from-thru model. */
  for {
    c <- futCol
    sel = d :~ THRU -> (d :~ "$exists" -> false)
    n <- c count Some(sel)
    if n > 0
  } for {seq <- (c find sel).coll[BSONDocument, Seq]()} for {e <- seq} {
    val usr = e.getAs[String](USER).get
    val id = e.getAs[String](ID).get
    val upd = Entry(UUID fromString usr, id, e.getAs[Long]("mils").get, Long.MaxValue, e.getAs[Mark](MARK).get)
    c update(d :~ USER -> usr :~ ID -> id, d :~ "$unset" -> (d :~ "mils" -> 1) :~ "$set" -> upd)
  }

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USER -> Ascending :: Nil) % s"bin-$USER-1" ::
      Index(THRU -> Ascending :: Nil) % s"bin-$THRU-1" ::
      /* Following two indexes are set to unique to prevent messing up timeline of entry states. */
      Index(ID -> Ascending :: MILS -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$MILS-1-uniq" ::
      Index(ID -> Ascending :: THRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$THRU-1-uniq" ::
      Index(s"$MARK.$UPRFX" -> Ascending :: s"$MARK.$REPR" -> Ascending :: Nil) % s"bin-$MARK.$UPRFX-1-$MARK.$REPR-1" ::
      Index(s"$MARK.$SUBJ" -> Text :: s"$MARK.$TAGS" -> Text :: s"$MARK.$COMMENT" -> Text :: Nil) %
        s"txt-$MARK.$SUBJ-$MARK.$TAGS-$MARK.$COMMENT" ::
      Index(s"$MARK.$TAGS" -> Ascending :: Nil) % s"bin-$MARK.$TAGS-1" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /** Saves an entry to the storage. */
  def create(entry: Entry): Future[Unit] = for {
    c <- futCol
    wr <- c insert entry
    _ <- if (wr.ok) Future successful() else Future failed new Exception(wr.writeErrors map (_.errmsg) mkString "; ")
  } yield ()

  /** Retrieves a current entry by user and id, None if not found. */
  def receive(user: UUID, id: String): Future[Option[Entry]] = for {
    c <- futCol
    optEnt <- (c find d :~ USER -> user.toString :~ ID -> id :~ curnt).one[Entry]
  } yield optEnt

  /** Retrieves all current entries for the user, sorted by 'from' time. */
  def receive(user: UUID): Future[Seq[Entry]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user.toString :~ curnt sort d :~ MILS -> 1).coll[Entry, Seq]()
  } yield seq

  /** Retrieves a current entry by user and url, None if not found. */
  def receive(url: String, user: UUID): Future[Option[Entry]] = for {
    c <- futCol
    set <- (c find d :~ USER -> user.toString :~ s"$MARK.$UPRFX" -> url.prefx :~ curnt).coll[Entry, Seq]()
  } yield set collectFirst { case e if e.mark.url.get == url => e }

  /** Retrieves all current entries for the user, constrained by a list of tags. Entry must have all tags to qualify. */
  def receiveTagged(user: UUID, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags) :~ curnt
    seq <- (c find sel).coll[Entry, Seq]()
  } yield seq

  /**
    * Retrieves all current entries with representations for the user, constrained by a list of tags. Entry must have
    * all tags to qualify.
    */
  def receiveRepred(user: UUID, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    sel0 = d :~ USER -> user.toString :~ s"$MARK.$REPR" -> (d :~ "$exists" -> true :~ "$ne" -> "") :~ curnt
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    seq <- (c find sel1).coll[Entry, Seq]()
  } yield seq

  /** Retrieves all tags existing in current entries for the user. */
  def receiveTags(user: UUID): Future[Set[String]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ curnt
    set <- (c find sel projection d :~ s"$MARK.$TAGS" -> 1).coll[BSONDocument, Set]()
  } yield for {
    d <- set
    ts <- d.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGS) getOrElse Set.empty
  } yield ts

  // TODO: receiveCorrelated -- given a vecrepr find (extremely) highly correlated others (i.e. same URL/content)

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Entry state must be
    * current and have all tags to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    sel0 = d :~ USER -> user.toString :~ curnt :~ "$text" -> (d :~ "$search" -> query)
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    pjn = d :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel1 projection pjn sort d :~ SCORE -> (d :~ "$meta" -> "textScore")).coll[Entry, Seq]()
  } yield seq

  /**
    * Updates current state of an entry with provided mark, looking up the entry up by user and id. Make sure to omit a
    * repr id in the update mark if changes were made to key fields of the mark.
    * Returns new current entry state.
    */
  def update(user: UUID, id: String, mark: Mark): Future[Entry] = for {
    c <- futCol
    now = DateTime.now.getMillis
    e = Entry(user, id, now, Long.MaxValue, mark)
    wr <- c update(d :~ USER -> user.toString :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ THRU -> now))
    wr <- wr ifOk c.insert(e)
    _ <- wr failIfError
  } yield e

  /**
    * Renames one tag in all user's entries that have it.
    * Returns updated entries number.
    */
  def updateTag(user: UUID, tag: String, rename: String): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$set" -> (d :~ s"$MARK.$TAGS.$$" -> rename), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /**
    * Updates all user's entries with new user id, effectively moving them to another user.
    * Returns the number of entries moved.
    */
  def move(thisUser: UUID, thatUser: UUID): Future[Int] = for {
    c <- futCol
    wr <- c update(d :~ USER -> thatUser.toString, d :~ "$set" -> (d :~ USER -> thisUser.toString), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Changes a set of entries outlined by user and a list of ids into 'thru' time of execution. */
  def delete(user: UUID, ids: Seq[String]): Future[Int] = for {
    c <- futCol
    now = DateTime.now.getMillis
    sel = d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$set" -> (d :~ THRU -> now), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a tag from all user's entries that have it. */
  def deleteTag(user: UUID, tag: String): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> tag
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> tag), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Adds a set of tags to each current entry from a list of ids. */
  def tag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$push" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$each" -> tags)), multi = true)
    _ <- wr failIfError
  } yield wr.nModified

  /** Removes a set of tags from each current entry from a list of ids if they have any of the tags. */
  def untag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Int] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids) :~ curnt
    wr <- c update(sel, d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$in" -> tags)), multi = true)
  } yield wr.nModified

  /** Retrieves a number of mongo _id values with URLs that belong to entry states without representations. */
  def findMissingReprs(n: Int): Future[Map[BSONObjectID, String]] = for {
    c <- futCol
    sel = d :~ s"$MARK.$UPRFX" -> (d :~ "$exists" -> true) :~ s"$MARK.$UPRFX" -> (d :~ "$ne" -> "".getBytes) :~
      s"$MARK.$REPR" -> (d :~ "$exists" -> false)
    seq <- (c find sel projection d :~ "_id" -> 1 :~ s"$MARK.$URL" -> 1).coll[BSONDocument, Seq]()
  } yield seq.map {
    d => d.getAs[BSONObjectID]("_id").get -> d.getAs[BSONDocument](MARK).get.getAs[String](URL).get
  }(collection.breakOut[Seq[BSONDocument], (BSONObjectID, String), Map[BSONObjectID, String]])

  /** Updates entry states from a list of _id values with provided representation id. */
  def updateMarkReprId(ids: Set[BSONObjectID], repr: String): Future[Int] = for {
    c <- futCol
    wr <- c update(d :~ "_id" -> (d :~ "$in" -> ids), d :~ "$set" -> (d :~ s"$MARK.$REPR" -> repr), multi = true)
    _ <- wr failIfError
  } yield wr.nModified
}
