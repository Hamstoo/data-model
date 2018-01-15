package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Highlight, PageCoord}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for highlights.
  */
class MongoHighlightDao(db: () => Future[DefaultDB])
                       (implicit marksDao: MongoMarksDao)
  extends MongoAnnotationDao[Highlight]("highlight", db) {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.utils._

  override val logger = Logger(classOf[MongoHighlightDao])
  override def dbColl(): Future[BSONCollection] = db().map(_ collection "highlights")

  Await.result(dbColl() map (_.indexesManager ensure indxs), 345 seconds)

  /** Update timeThru on an existing highlight and insert a new one with modified values. */
  def update(usr: UUID,
             id: String,
             pos: Highlight.Position,
             prv: Highlight.Preview,
             coord: Option[PageCoord]): Future[Highlight] = for {
    c <- dbColl()
    now = TIME_NOW
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c.findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    hl = wr.result[Highlight].get.copy(pos = pos,
                                       preview = prv,
                                       pageCoord = coord,
                                       memeId = None,
                                       timeFrom = now,
                                       timeThru = INF_TIME)
    wr <- c insert hl
    _ <- wr failIfError
  } yield hl
}
