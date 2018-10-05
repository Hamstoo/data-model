/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.InlineNote
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for inline notes (of which there can be many per mark) as opposed to comments (of which there
  * is only one per mark).
  */
@Singleton
class InlineNoteDao @Inject()(implicit db: () => Future[DefaultDB],
                              marksDao: MarkDao,
                              userDao: UserDao,
                              pagesDao: PageDao) extends AnnotationDao[InlineNote]("inline note") {

  import com.hamstoo.utils._

  override def dbColl(): Future[BSONCollection] = db().map(_ collection "comments")

  Await.result(dbColl() map (_.indexesManager ensure indxs), 366 seconds)
}
