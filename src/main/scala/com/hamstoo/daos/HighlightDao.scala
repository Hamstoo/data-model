/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Highlight
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for highlights.
  */
@Singleton
class HighlightDao @Inject()(implicit db: () => Future[DefaultDB],
                             marksDao: MarkDao,
                             userDao: UserDao,
                             pagesDao: PageDao) extends AnnotationDao[Highlight]("highlight") {

  import com.hamstoo.utils._

  override def dbColl(): Future[BSONCollection] = db().map(_ collection "highlights")

  Await.result(dbColl() map (_.indexesManager ensure indxs), 345 seconds)
}
