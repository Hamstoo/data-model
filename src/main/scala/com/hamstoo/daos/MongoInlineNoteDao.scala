package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, PageCoord}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for inline notes (of which there can be many per mark) as opposed to comments (of which there
  * is only one per mark).
  */
class MongoInlineNoteDao(db: () => Future[DefaultDB])(implicit marksDao: MongoMarksDao)
                                        extends MongoAnnotationDao[InlineNote]("inline note", db) {

  import com.hamstoo.models.InlineNote._
  import com.hamstoo.utils._

  override val logger = Logger(classOf[MongoInlineNoteDao])
  override def dbColl(): Future[BSONCollection] = db().map(_ collection "comments")

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: MARKID -> Ascending :: Nil) % s"bin-$USR-1-$MARKID-1" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 366 seconds)

  /** Update timeThru on an existing inline note and insert a new one with modified values. */
  def update(usr: UUID,
             id: String,
             pos: InlineNote.Position,
             coord: Option[PageCoord]): Future[InlineNote] = for {
    c <- dbColl()
    now = TIME_NOW
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    ct = wr.result[InlineNote].get.copy(pos = pos,
                                        pageCoord = coord,
                                        memeId = None,
                                        timeFrom = now,
                                        timeThru = Long.MaxValue)
    wr <- c insert ct
    _ <- wr failIfError
  } yield ct
}
