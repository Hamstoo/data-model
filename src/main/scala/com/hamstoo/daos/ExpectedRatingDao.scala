/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Mark.ExpectedRating
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for MongoDB `eratings` collection.
  * @param db  Future[DefaultDB] database connection returning function.
  */
@Singleton
class ExpectedRatingDao @Inject()(implicit db: () => Future[DefaultDB])
    extends ReprEngineProductDao[ExpectedRating]("expected rating") {

  import com.hamstoo.utils._
  import com.hamstoo.models.Mark.{ID, TIMETHRU}

  override def dbColl(): Future[BSONCollection] = db().map(_.collection("eratings"))

  // ensure indexes
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 394 seconds)
}
