package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Highlight
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONDocument, BSONElement, Producer}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for highlights.
  */
class MongoHighlightDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.utils._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}

  private val futCol: Future[BSONCollection] = db map (_ collection "highlights")

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: UPRF -> Ascending :: Nil) % s"bin-$USR-1-$UPRF-1" ::
      Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
        s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  def create(hl: Highlight): Future[Unit] = for {
    c <- futCol
    wr <- c insert hl
    _ <- wr failIfError
  } yield ()

  def receive(usr: UUID, id: String): Future[Option[Highlight]] = for {
    c <- futCol
    mbHl <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[Highlight]
  } yield mbHl

  def receive(url: String, usr: UUID): Future[Seq[Highlight]] = for {
    c <- futCol
    seq <- (c find d :~ USR -> usr :~ UPRF -> url.prefx :~ curnt).coll[Highlight, Seq]()
  } yield seq filter (_.url == url)

  def update(usr: UUID, id: String, pos: HLPos, prv: HLPreview): Future[Highlight] = for {
    c <- futCol
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
    c <- futCol
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()
}
