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
import reactivemongo.bson.{BSONArray, BSONDocument}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future



@Singleton
class RecommendationDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[RecommendationDao])

  // database collection
  private def recommendationDB(): Future[BSONCollection] = db().map(_.collection("recommendations"))

  // indexes for this mongo collection
  recommendationDB().map(_.indexesManager.ensure(Index(Seq("userId" -> IndexType.Ascending), name = Some("userId_idx"))))
  recommendationDB().map(_.indexesManager.ensure(Index(Seq("ts" -> IndexType.Descending), name = Some("ts_idx"))))


  def insertOne(recommendation: Recommendation) = for {
    c <- recommendationDB()
    _ = logger.info(s"Inserting: $recommendation")
    wr <- c.insert(recommendation)
    _ <- wr.failIfError
  } yield logger.info(s"Successfully inserted: $recommendation")

  def insertMany(recommendations: Seq[Recommendation]) = {
    recommendations foreach insertOne
    Future(Unit)
  }


  def retrive(user: UUID) = {
    logger.info(s"Retrieving last week feeds for user $user")
    val oneWeekAgo = new DateTime() minusDays 7 getMillis
    val mongoTimeObj = BSONDocument(
      "date" -> BSONDocument(
        "$gte" -> oneWeekAgo,
        "$lte" -> new DateTime().getMillis
      ))
    val q = BSONDocument("$and" -> BSONArray(mongoTimeObj, BSONDocument("userId" -> user.toString)))
    for {
      c <- recommendationDB()
      r <- c.find(q).sort(d :~ "ts" -> -1).coll[Recommendation, Seq]()
    } yield r
  }
}
