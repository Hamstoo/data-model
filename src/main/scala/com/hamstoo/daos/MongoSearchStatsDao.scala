package com.hamstoo.daos

import com.hamstoo.models.SearchStats
import com.hamstoo.models.SearchStats._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
  * MongoDB data access object for `searchstats` collection.  Search stats are stats on the number of times
  * a user has visited a mark's URL (via Hamstoo) or its full-page view.
  */
class MongoSearchStatsDao(db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "searchstats")

  private val indxs: Map[String, Index] =
    Index(QUERY -> Ascending :: Nil, unique = true) % s"bin-$QUERY-1-uniq" :: Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 334 seconds)

  def addUrlClick(query: String, id: String, url: String, weight: Double, index: Int): Future[Unit] =
    for {
      c <- dbColl()
      sel = d :~ QUERY -> query
      mbss <- (c find sel).one[SearchStats]
      upd = mbss getOrElse SearchStats(query) incUrl(url, id, weight, index)
      wr <- c update(sel, upd, upsert = true)
      _ <- wr.failIfError
    } yield ()

  def addFpvClick(query: String, id: String, url: Option[String], weight: Double, index: Int): Future[Unit] =
    for {
      c <- dbColl()
      sel = d :~ QUERY -> query
      mbss <- (c find sel).one[SearchStats]
      upd = mbss getOrElse SearchStats(query) incFpv(url, id, weight, index)
      wr <- c update(sel, upd, upsert = true)
      _ <- wr.failIfError
    } yield ()
}
