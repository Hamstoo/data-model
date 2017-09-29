package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.InlineNote
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global // "Prefer a dedicated ThreadPool for IO-bound tasks" [https://www.beyondthelines.net/computing/scala-future-and-execution-context/]
import scala.concurrent.Future


class MongoInlineNoteDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.InlineNote._
  import com.hamstoo.utils._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}

  private val futColl: Future[BSONCollection] = db map (_ collection "comments")

  // reduce size of existing `uPref`s down to URL_PREFIX_LENGTH to be consistent with MongoMarksDao (version 0.9.16)
  for {
    c <- futColl
    sel = d :~ "$where" -> s"Object.bsonsize({$UPREF:this.$UPREF})>$URL_PREFIX_LENGTH+19"
    longPfxed <- c.find(sel).coll[InlineNote, Seq]()
    _ <- Future.sequence { longPfxed.map { repr => // uPref will have been overwritten upon construction
      c.update(d :~ ID -> repr.id :~ TIMEFROM -> repr.timeFrom, d :~ "$set" -> (d :~ UPREF -> repr.uPref))
    }}
  } yield ()

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: UPREF -> Ascending :: Nil) % s"bin-$USR-1-$UPREF-1" ::
      Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
        s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
      Nil toMap;
  futColl map (_.indexesManager ensure indxs)

  def create(ct: InlineNote): Future[Unit] = for {
    c <- futColl
    wr <- c insert ct
    _ <- wr failIfError
  } yield ()

  def retrieve(usr: UUID, id: String): Future[Option[InlineNote]] = for {
    c <- futColl
    optCt <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[InlineNote]
  } yield optCt

  def retrieveByUrl(usr: UUID, url: String): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ UPREF -> url.binaryPrefix :~ curnt).coll[InlineNote, Seq]()
  } yield seq filter (_.url == url)

  /*def retrieveSortedByPageCoord(url: String, usr: UUID): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ UPREF -> url.binaryPrefix :~ curnt).coll[Comment, Seq]()
  } yield seq filter (_.url == url) sortWith { case (a, b) => PageCoord.sortWith(a.pageCoord, b.pageCoord) }*/

  // TODO: merge w/ HighlightDao?????




  def update(usr: UUID, id: String, pos: InlineNote.Position): Future[InlineNote] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TILL -> now), fetchNewObject = true)
    ct = wr.result[InlineNote].get.copy(pos = pos, memeId = None, timeFrom = now, timeThru = Long.MaxValue)
    wr <- c insert ct
    _ <- wr failIfError
  } yield ct

  def delete(usr: UUID, id: String): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()

  /**
    * Be carefull, expansive operation.
    * @return
    */
  def receiveAll(): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d).coll[InlineNote, Seq]()
  } yield seq
}
