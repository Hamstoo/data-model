/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.Recommendation
import javax.inject.Singleton

import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * TODO 266
  *
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

  /**
    * TODO 266
    * @param recommendation
    * @return
    */
  def insertOne(recommendation: Recommendation): Future[Unit] = for {
    c <- dbColl()
    _ = logger.info(s"Inserting: $recommendation")
    wr <- c.insert(recommendation)
    _ <- wr.failIfError
  } yield logger.info(s"Successfully inserted: $recommendation")

  /**
    * TODO 266
    * @param recommendations
    * @return
    */
  def insertMany(recommendations: Seq[Recommendation]): Future[Unit] = {
    recommendations foreach insertOne
    Future(Unit)
  }

  /**
    * TODO 266
    * @param userId
    * @param nDaysBack  Only retrieve recommendations from, at most, this far back in time.
    * @return
    */
  def retrieve(userId: UUID, nDaysBack: Int = RecommendationDao.DEFAULT_DAYS_BACK): Future[Seq[Recommendation]] = {
    logger.info(s"Retrieving $nDaysBack days' recommendations for user $userId")

    // TODO 266: do we really only want to retrieve recommendations from up to 7 days back?  or is the purpose of the
    // TODO 266:   7 to limit the keywords with which we're computing recentAutoGenKws ???

    val oneWeekAgo = new DateTime().minusDays(nDaysBack).getMillis
    val q = d :~ USR -> userId :~
                 TIMESTAMP -> (d :~ "$gte" -> oneWeekAgo) :~ // TODO 266: these 2 TIMESTAMP were previously combined. was there a reason?
                 TIMESTAMP -> (d :~ "$lte" -> new DateTime().getMillis) // TODO 266: is it even possible to have future recs?
    for {
      c <- dbColl()
      //r <- c.find(d :~ USR -> user.toString).sort(d :~ TIMESTAMP -> -1).coll[Recommendation, Seq]()
      r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Recommendation, Seq]()
    } yield r
  }
}

object RecommendationDao {
  val DEFAULT_DAYS_BACK = 7
}
