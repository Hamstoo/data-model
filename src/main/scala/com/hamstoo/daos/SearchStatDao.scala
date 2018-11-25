/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.SearchStats
import com.hamstoo.models.SearchStats._
import reactivemongo.api.{Cursor, DefaultDB}
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.{Ascending, Text}
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
  * MongoDB data access object for `searchstats` collection.  Search stats are stats on the number of times
  * a user has visited a mark's URL (via Hamstoo) or its full-page view.
  */
@Singleton
class SearchStatDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._

  // using searchstats2 here following the 2018-1-8 complete rewrite of the search stats functionality
  private def dbColl(): Future[BSONCollection] = db().map(_ collection "searchstats2")

  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(USR -> Ascending :: MARKID -> Ascending :: QUERY -> Ascending :: Nil) % s"bin-$USR-1-$MARKID-1-$QUERY-1" ::
    // text index (there can be only one per collection)
    Index(USR -> Ascending :: QUERY -> Text :: Nil) % s"bin-$USR-1--txt-$QUERY" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 334 seconds)

  /**
    * Record a user clicking a FPV or URL while executing a particular search query along with search term relevance
    * and index in the list of search results.
    */
  def addClick(input: SearchStats): Future[Unit] = for {
    c <- dbColl()
    usr = input.userId.fold(d :~ USR -> (d :~ "$exists" -> false))(id => d :~ USR -> id)
//    seq <- c.find(d :~ usr :~ MARKID -> input.markId :~ QUERY -> input.query, Option.empty[SearchStats]).coll[SearchStats, Seq]()
    seq <- c.getMongoResults[SearchStats, Seq](d :~ usr :~ MARKID -> input.markId :~ QUERY -> input.query, Option.empty[SearchStats])

    // facet args must be identical, but note that *implicit*/default facet args may change over time
    mb = seq.find(x => x.facetArgs == input.facetArgs && x.labels == input.labels)

    upd = mb.fold(input)(ss => ss.copy(clicks = ss.clicks ++ input.clicks))
    wr <- c.update(d :~ ID -> upd.id, upd, upsert = true)
    _ <- wr.failIfError
  } yield ()
}
