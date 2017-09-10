package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.{Mark, MarkData, Page}
import org.joda.time.DateTime
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

  private val futCol: Future[BSONCollection] = db map (_ collection "entries")

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
  futCol map (_.indexesManager ensure indxs)

  /** Saves a mark to the storage or updates if the user already has a mark with such URL. */
  def create(mark: Mark): Future[Mark] = for {
    c <- futCol
    now = DateTime.now.getMillis
    optMark <- mark.mark.url match {
      case Some(url) => receive(url, mark.userId) /* Looks for this user's marks with same URL if defined. */
      case None => Future successful None
    }
    newMk <- optMark match {
      case Some(m) => for {
        wr <- c update(d :~ USER -> mark.userId :~ ID -> m.id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> now))
        wr <- wr ifOk (c insert m.copy(mark = mark.mark, timeFrom = now))
        _ <- wr failIfError
      } yield m /* Updates existing same URL mark with new data if such mark was found. */
      case None => for {
        wr <- c insert mark
        _ <- wr failIfError
      } yield mark /* Saves provided mark as is if it's URL is unique for the user. */
    }
  } yield newMk

  /** Retrieves a current mark by user and id, None if not found. */
  def receive(user: UUID, id: String): Future[Option[Mark]] = for {
    c <- futCol
    optEnt <- (c find d :~ USER -> user :~ ID -> id :~ curnt).one[Mark]
  } yield optEnt

  /** Retrieves all current marks for the user, sorted by 'from' time. */
  def receive(user: UUID): Future[Seq[Mark]] = for {
    c <- futCol
    seq <- (c find d :~ USER -> user :~ curnt sort d :~ TIMEFROM -> -1).coll[Mark, Seq]()
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
    seq <- (c find sel sort d :~ TIMEFROM -> -1).coll[Mark, Seq]()
  } yield seq

  /**
    * Retrieves all current marks with representations for the user, constrained by a list of tags. Mark must have
    * all tags to qualify.
    */
  def receiveRepred(user: UUID, tags: Set[String]): Future[Seq[Mark]] = for {
    c <- futCol
    exst = d :~ "$exists" -> true :~ "$ne" -> ""
    sel0 = d :~ USER -> user :~ curnt :~ "$or" -> BSONArray(d :~ PUBREPR -> exst, d :~ PRVREPR -> exst)
    sel1 = if (tags.isEmpty) sel0 else sel0 :~ s"$MARK.$TAGS" -> (d :~ "$all" -> tags)
    seq <- (c find sel1).coll[Mark, Seq]()
  } yield seq

  /** Retrieves all tags existing in current marks for the user. */
  def receiveTags(user: UUID): Future[Set[String]] = for {
    c <- futCol
    sel = d :~ USER -> user :~ curnt
    set <- (c find sel projection d :~ s"$MARK.$TAGS" -> 1 :~ "_id" -> 0).coll[BSONDocument, Set]()
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
    * Updates current state of a mark with provided MarkData, looking the mark up by user and id.
    * Returns new current mark state.
    */
  def update(user: UUID, id: String, mdata: MarkData): Future[Mark] = for {
    c <- futCol
    _ <- mdata.url match {
      case Some(url) => receive(url, user) map {
        case Some(_) => Future failed new UnsupportedOperationException
        case _ => Future successful None
      }
      case _ => Future successful None
    }
    now = DateTime.now.getMillis
    sel = d :~ USER -> user :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    mk = wr.result[Mark].get
    reps = if (mdata.url == mk.mark.url) (mk.pubRepr, mk.privRepr) else (None, None)
    newMk = mk.copy(mark = mdata, pubRepr = reps._1, privRepr = reps._2, timeFrom = now, timeThru = Long.MaxValue)
    wr <- c insert newMk
    _ <- wr failIfError
  } yield newMk

  /** Appends provided string to mark's array of page sources. */
  def addPageSource(user: UUID, id: String, page: Page): Future[Unit] = for {
    c <- futCol
    wr <- c update(d :~ USER -> user :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ PAGE -> page))
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
    wr <- c update(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), multi = true)
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

  /** Retrieves a list of n marks that require representations. Intentionally not filtering for `curnt` marks. */
  def findMissingReprs(n: Int): Future[Seq[Mark]] = for {
    c <- futCol
    sel = d :~ UPRFX -> (d :~ "$exists" -> true) :~ UPRFX -> (d :~ "$ne" -> "".getBytes) :~
      "$or" -> BSONArray(d :~ PUBREPR -> (d :~ "$exists" -> false), d :~ s"$PAGE" -> (d :~ "$exists" -> true))
    seq <- (c find sel).coll[Mark, Seq](n)
  } yield seq

  /**
    * Updates a mark state with provided representation id. this method is typically called as a result
    * of findMissingReprs, so if the latter is picking up non-`curnt` marks, then the former needs to be also, o/w
    * repr-engine's MongoClient.refresh could get stuck hopelessly trying to assign reprs to the same non-current
    * marks over and over.
    */
  def updatePublicReprId(id: String, timeFrom: Long, repId: String): Future[Unit] = for {
    c <- futCol
    sel = d :~ ID -> id :~ TIMEFROM -> timeFrom
    wr <- c update(sel, d :~ "$set" -> (d :~ PUBREPR -> repId))
    _ <- wr failIfError
  } yield ()

  /**
    * Updates a mark state with provided private representation id and clears out processed page source. this method is
    * typically called as a result of findMissingReprs, so if the latter is picking up non-`curnt` marks, then the
    * former needs to be also, o/w repr-engine's MongoClient.refresh could get stuck hopelessly trying to assign
    * reprs to the same non-current marks over and over.
    *
    * @param user     - user ID of mark's owner; serves as a safeguard against inadverent private content mixups
    * @param id       - mark ID
    * @param timeFrom - mark version timestamp
    * @param repId    - representation ID
    * @param page     - processed page source to clear out from the mark
    */
  def updatePrivateReprId(user: UUID, id: String, timeFrom: Long, repId: String, page: Page): Future[Unit] = for {
    c <- futCol
    sel = d :~ ID -> id :~ TIMEFROM -> timeFrom
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ PRVREPR -> repId), fetchNewObject = true) /* Writes new
    private representation ID into the mark and retrieves updated document in the result. */
    mk = wr.result[Mark].get
    _ <- if (mk.page contains page) for {
      wr <- c update(sel, d :~ "$unset" -> (d :~ PAGE -> 1))
      _ <- wr failIfError
    } yield () else Future successful {} /* Removes page source from the mark in case it's the same as the one
    processed. */
  } yield ()


  def insertBookmarks(marksStream : Stream[Option[Mark]]): Unit = {
    futCol.map(marksCollection => marksCollection.bulkInsert(marksStream.map(_.asInstanceOf[BSONDocument]), false))
  }

}
