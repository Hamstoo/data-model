package com.hamstoo.daos

import com.hamstoo.models.SearchStats
import com.hamstoo.models.SearchStats._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoSearchStatsDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "searchstats")
  private val d = BSONDocument.empty

  private val indxs: Map[String, Index] =
    Index(QUERY -> Ascending :: Nil, unique = true) % s"bin-$QUERY-1-uniq" :: Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  def addClick(query: String, id: String, url: String, weight: Double, index: Int, fpv: Boolean): Future[Unit] = for {
    c <- futCol
    vis = if (fpv) VISFPV else VISURL
    st = if (fpv) STFPV else STURL
    sel = d :~ QUERY -> query
    mbss <- (c find sel).one[SearchStats]
    ss = mbss getOrElse SearchStats(query)
    upd = if (fpv) ss incFpv (url, id, weight, index) else ss incUrl (url, id, weight, index)
    wr <- c update(sel, upd, upsert = true)
    _ <- wr.failIfError
  } yield ()
}
