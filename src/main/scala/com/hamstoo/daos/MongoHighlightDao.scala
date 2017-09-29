package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Highlight
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global // "Prefer a dedicated ThreadPool for IO-bound tasks" [https://www.beyondthelines.net/computing/scala-future-and-execution-context/]
import scala.concurrent.Future

/**
  * Data access object for highlights.
  */
class MongoHighlightDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}
  import com.hamstoo.utils._

  private val futColl: Future[BSONCollection] = db map (_ collection "highlights")

  // reduce size of existing `uPref`s down to URL_PREFIX_LENGTH to be consistent with MongoMarksDao (version 0.9.16)
  for {
    c <- futColl
    sel = d :~ "$where" -> s"Object.bsonsize({$UPREF:this.$UPREF})>$URL_PREFIX_LENGTH+19"
    longPfxed <- c.find(sel).coll[Highlight, Seq]()
    _ <- Future.sequence { longPfxed.map { hlgt => // uPref will have been overwritten upon construction
      c.update(d :~ ID -> hlgt.id :~ TIMEFROM -> hlgt.timeFrom, d :~ "$set" -> (d :~ UPREF -> hlgt.uPref))
    }}
  } yield ()

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: UPREF -> Ascending :: Nil) % s"bin-$USR-1-$UPREF-1" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  
  futColl map (_.indexesManager ensure indxs)

  def create(hl: Highlight): Future[Unit] = for {
    c <- futColl
    wr <- c insert hl
    _ <- wr failIfError
  } yield ()

  def retrieve(usr: UUID, id: String): Future[Option[Highlight]] = for {
    c <- futColl
    mbHl <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[Highlight]
  } yield mbHl

  def retrieveByUrl(usr: UUID, url: String): Future[Seq[Highlight]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ UPREF -> url.binaryPrefix :~ curnt).coll[Highlight, Seq]()
  } yield seq filter (_.url == url)

  def update(usr: UUID, id: String, pos: Highlight.Position, prv: Highlight.Preview): Future[Highlight] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    hl = wr.result[Highlight].get.copy(
      pos = pos,
      preview = prv,
      memeId = None,
      timeFrom = now,
      timeThru = INF_TIME)
    wr <- c insert hl
    _ <- wr failIfError
  } yield hl

  def delete(usr: UUID, id: String): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()
}
