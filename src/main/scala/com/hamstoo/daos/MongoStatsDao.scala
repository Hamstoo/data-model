package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark._
import com.hamstoo.models.{Stats, StatsDay}
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for usage stats. */
class MongoStatsDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils._

  // database collections
  private val futStatsCol: Future[BSONCollection] = db map (_ collection "userstats")
  private val futImportsCol: Future[BSONCollection] = db map (_ collection "imports")
  private val futEntriesCol: Future[BSONCollection] = db map (_ collection "entries")

  private val IMPT = "imports"
  private val TIME = "time"
  private val USR = "user"

  /* Ensure the mongo collection has proper index: */
  private val indxs: Map[String, Index] =
    Map(Index(USR -> Ascending :: TIME -> Ascending :: Nil) % s"bin-$USR-1-$TIME-1")
  futStatsCol map (_.indexesManager ensure indxs)

  /** Adds a timestamp record for the user. */
  def punch(userId: UUID): Future[Unit] = for {
    c <- futStatsCol
    wr <- c insert d :~ USR -> userId.toString :~ TIME -> DateTime.now.getMillis
    _ <- wr failIfError
  } yield ()

  /** Increments user's imports count by `n`. */
  def imprt(userId: UUID, n: Int): Future[Unit] = for {
    c <- futImportsCol
    wr <- c update(d :~ "_id" -> userId.toString, d :~ "$inc" -> (d :~ IMPT -> n), upsert = true)
    _ <- wr failIfError
  } yield ()

  /** Retrieves user's usage stats. */
  def stats(userId: UUID, offsetMinutes: Int): Future[Stats] = for {
    cS <- futStatsCol
    cI <- futImportsCol
    cE <- futEntriesCol
    marks <- cE count Some(d :~ USER -> userId.toString :~ TIMETHRU -> Long.MaxValue)
    imports <- cI.find(d :~ "_id" -> userId.toString).one[BSONDocument]
    /* Count total number of times user added a mark: */
    total <- cS count Some(d :~ USR -> userId.toString)
    /* Query all records in past four weeks, correcting for user's current timezone: */
    sel = d :~ USR -> userId.toString :~ TIME -> (d :~ "$gt" -> (DateTime.now minusWeeks 4 getMillis))
    seq <- (cS find sel projection d :~ TIME -> 1).coll[BSONDocument, Seq]()
  } yield {
    val firstDay = DateTime.now minusMinutes offsetMinutes + 38880
    /* == 60 * 24 * 27 minutes or 27 days */
    val format = "dd MMM"
    /* Group timestamps into collections by day string and take the number of records for each day: */
    val values: Map[String, Int] = seq groupBy { d =>
      new DateTime(d.getAs[Long](TIME).get) minusMinutes offsetMinutes toString format
    } mapValues (_.size) withDefaultValue 0
    /* Get last 28 dates in user's timezone and pair them with numbers of marks: */
    val days: Seq[StatsDay] = for (i <- 0 to 27) yield {
      val s = firstDay plusDays i toString format
      StatsDay(s, values(s))
    }
    val imported = imports flatMap (_.getAs[Int](IMPT)) getOrElse 0
    Stats(marks, imported, total, days, (0 /: days) (_ + _.marks), days.reverse maxBy (_.marks))
  }
}
