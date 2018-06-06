/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.{InlineNote, PageCoord}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for inline notes (of which there can be many per mark) as opposed to comments (of which there
  * is only one per mark).
  */
class InlineNoteDao @Inject()(implicit db: () => Future[DefaultDB],
                              marksDao: MarkDao,
                              userDao: UserDao,
                              pagesDao: PageDao) extends AnnotationDao[InlineNote]("inline note") {

  import com.hamstoo.models.InlineNote._
  import com.hamstoo.utils._

  override val logger = Logger(classOf[InlineNoteDao])
  override def dbColl(): Future[BSONCollection] = db().map(_ collection "comments")

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
