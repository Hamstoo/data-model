package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Entry._
import com.hamstoo.models.Mark._
import com.hamstoo.models.{Entry, Mark}
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoMarksDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedQB, StrWithBinaryPrefix, digestWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "entries")
  private val d = BSONDocument.empty
  /* Ensure this mongo collection has proper indexes: */
  for {
    col <- futCol
    im = col.indexesManager
  } { // TODO: check indexes list
    im ensure Index(USER -> Ascending :: MILS -> Ascending :: Nil)
    im ensure Index(USER -> Ascending :: ID -> Ascending :: Nil)
    im ensure Index(USER -> Ascending :: s"$MARK.$UPRFX" -> Ascending :: Nil)
    im ensure Index(USER -> Ascending :: s"$MARK.$UPRFX" -> Ascending :: Nil)
    im ensure
      Index(USER -> Ascending :: s"$MARK.$SUBJ" -> Text :: s"$MARK.$TAGS" -> Text :: s"$MARK.$COMMENT" -> Text :: Nil)
    im ensure Index(s"$MARK.$TAGS" -> Ascending :: Nil)
    im ensure Index(USER -> Ascending :: s"$MARK.$UPRFX" -> Ascending :: Nil)
    im ensure Index(s"$MARK.$UPRFX" -> Ascending :: s"$MARK.$REPR" -> Ascending :: Nil)
  }

  /** Saves an entry to the storage. */
  def create(entry: Entry): Future[Either[String, Entry]] = for {
    c <- futCol
    wr <- c insert entry
  } yield digestWriteResult(wr, entry)

  /** Retrieves an entry by user and id, None if not found. */
  def receive(user: UUID, id: String): Future[Option[Entry]] = for {
    c <- futCol
    optEnt <- (c find d :~ USER -> user.toString :~ ID -> id).one[Entry]
  } yield optEnt

  /** Retrieves all entries for the user. */
  def receive(user: UUID): Future[Seq[Entry]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user.toString sort d :~ MILS -> 1).coll[Entry, Seq]()
  } yield seq

  /** Retrieves an entry by user and url, None if not found. */
  def receive(url: String, user: UUID): Future[Option[Entry]] = for {
    c <- futCol
    set <- (c find d :~ USER -> user.toString :~ s"$MARK.$UPRFX" -> url.prefx).coll[Entry, Seq]()
  } yield set collectFirst { case e if e.mark.url.get == url => e }

  /** Retrieves all entries for the user, constrained by a list of tags. Entry must have all tags to qualify. */
  def receiveTagged(user: UUID, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)).coll[Entry, Seq]()
  } yield seq

  /**
    * Retrieves all entries with representations for the user, constrained by a list of tags. Entry must have all
    * tags to qualify.
    */
  def receiveRepred(user: UUID, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    sel0 = d :~ USER -> user.toString :~ s"$MARK.$REPR" -> (d :~ "$exists" -> true :~ "$ne" -> "")
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    seq <- (c find sel1).coll[Entry, Seq]()
  } yield seq

  /** Retrieves all existing tags for the user. */
  def receiveTags(user: UUID): Future[Set[String]] = for {
    c <- futCol
    set <- (c find d :~ USER -> user.toString projection d :~ s"$MARK.$TAGS" -> 1).coll[BSONDocument, Set]()
  } yield for {
    d <- set
    ts <- d.getAs[BSONDocument](MARK).get.getAs[Set[String]](TAGS) getOrElse Set.empty
  } yield ts

  // TODO: receiveCorrelated -- given a vecrepr find (extremely) highly correlated others (i.e. same URL/content)

  /**
    * Executes a search using text index with sorting in user's marks, constrained by tags. Entry must have all tags
    * to qualify.
    */
  def search(user: UUID, query: String, tags: Set[String]): Future[Seq[Entry]] = for {
    c <- futCol
    sel0 = d :~ USER -> user.toString :~ "$text" -> (d :~ "$search" -> query)
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    pjn = d :~ SCORE -> (d :~ "$meta" -> "textScore")
    seq <- (c find sel1 projection pjn sort d :~ SCORE -> (d :~ "$meta" -> "textScore")).coll[Entry, Seq]()
  } yield seq

  /** Updates one entry by user and id, if it exists. */
  def update(user: UUID, id: String, mark: Mark): Future[Either[String, Mark]] = for {
    c <- futCol
    wr <- c update(d :~ USER -> user.toString :~ ID -> id, d :~ "$set" -> (d :~ MARK -> mark))
  } yield digestWriteResult(wr, mark)

  /** Renames one tag in all user's entries that have it. */
  def updateTag(user: UUID, tag: String, rename: String): Future[Either[String, String]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> tag
    upd = d :~ "$set" -> (d :~ s"$MARK.$TAGS.$$" -> rename)
    wr <- c update(sel, upd, multi = true)
  } yield digestWriteResult(wr, rename)

  /** Updates all user's entries with new user id, effectively moving them to another user. */
  def move(thisUser: UUID, thatUser: UUID): Future[Either[String, UUID]] = for {
    c <- futCol
    wr <- c update(d :~ USER -> thatUser.toString, d :~ "$set" -> (d :~ USER -> thisUser.toString), multi = true)
  } yield digestWriteResult(wr, thatUser)

  /** Removes a set of entries by user and a list of ids. */
  def delete(user: UUID, ids: Seq[String]): Future[Either[String, Seq[String]]] = for {
    c <- futCol
    wr <- c remove d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids)
  } yield digestWriteResult(wr, ids)

  /** Removes a tag from all user's entries that have it. */
  def deleteTag(user: UUID, tag: String): Future[Either[String, String]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ s"$MARK.$TAGS" -> tag
    upd = d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> tag)
    wr <- c update(sel, upd, multi = true)
  } yield digestWriteResult(wr, tag)

  /** Adds a set of tags to each entry from a list of ids. */
  def tag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Either[String, Set[String]]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids)
    upd = d :~ "$push" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$each" -> tags))
    wr <- c update(sel, upd, multi = true)
  } yield digestWriteResult(wr, tags)

  /** Removes a set of tags from each entry from a list of ids if they have any of the tags. */
  def untag(user: UUID, ids: Seq[String], tags: Set[String]): Future[Either[String, Set[String]]] = for {
    c <- futCol
    sel = d :~ USER -> user.toString :~ ID -> (d :~ "$in" -> ids)
    upd = d :~ "$pull" -> (d :~ s"$MARK.$TAGS" -> (d :~ "$in" -> tags))
    wr <- c update(sel, upd, multi = true)
  } yield digestWriteResult(wr, tags)

  /** Retrieves a number of ids with URLs that belong to entries without representations. */
  def findMissingReprs(n: Int): Future[Map[BSONObjectID, String]] = for {
    c <- futCol
    sel = d :~ s"$MARK.$UPRFX" -> (d :~ "$exists" -> true) :~ s"$MARK.$UPRFX" -> (d :~ "$ne" -> "".getBytes) :~
      s"$MARK.$REPR" -> (d :~ "$exists" -> false)
    seq <- (c find sel projection d :~ s"$MARK.$URL" -> 1).coll[BSONDocument, Seq]()
  } yield seq.map {
    d => d.getAs[BSONObjectID](ID).get -> d.getAs[BSONDocument](MARK).get.getAs[String](URL).get
  }(collection.breakOut[Seq[BSONDocument], (BSONObjectID, String), Map[BSONObjectID, String]])

  /** Updates entries from a list of ids with provided representation id. */
  def updateMarkReprId(ids: Set[BSONObjectID], repr: String): Future[Either[String, String]] = for {
    c <- futCol
    wr <- c update(d :~ ID -> (d :~ "$in" -> ids), d :~ "$set" -> (d :~ s"$MARK.$REPR" -> repr), multi = true)
  } yield digestWriteResult(wr, repr)
}
