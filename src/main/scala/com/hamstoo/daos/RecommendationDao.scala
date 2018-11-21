/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Recommendation
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * User recommendations/feeds DAO.
  */
@Singleton
class RecommendationDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import Recommendation._
  import com.hamstoo.utils._
  val logger: Logger = Logger(getClass)

  // database collection
  private def dbColl(): Future[BSONCollection] = db().map(_.collection("recommendations"))

  // indexes for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> IndexType.Ascending :: TIMESTAMP -> IndexType.Descending :: Nil) % s"bin-$USR-1-$TIMESTAMP-1" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 134 seconds)

  /** Insert one recommendation in MongoDB */
  def insert(recommendation: Recommendation): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Inserting: $recommendation")
    wr <- c.insert(recommendation)
    _ <- wr.failIfError
  } yield logger.debug(s"Successfully inserted: $recommendation")

  /**
    * Get recommendations assigned to a user, saved in the past `nDaysBack` days.
    * @param userId     Every recommendation is linked to an user, by given UUID.
    * @param nDaysBack  Default: 100.  Only retrieve recommendations from, at most, this far back in time.  This
    *                   is what will be displayed to the user on his My Feeds page.  It has nothing to do with the
    *                   number of days over which to compute recentAutoGenKws.
    */
  def retrieve(userId: UUID, nDaysBack: Int = 100): Future[Seq[Recommendation]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving $nDaysBack days' recommendations for user $userId")
    pastDate = new DateTime().minusDays(nDaysBack).getMillis
    q = d :~ USR -> userId :~ TIMESTAMP -> (d :~ "$gte" -> pastDate)
    //r <- c.find(d :~ USR -> user.toString).sort(d :~ TIMESTAMP -> -1).coll[Recommendation, Seq]()
    r <- c.find(q, Option.empty[Recommendation]).sort(d :~ TIMESTAMP -> -1).coll[Recommendation, Seq]()
  } yield r
}
