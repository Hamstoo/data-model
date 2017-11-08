package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, UserStats, UserStatsDay}
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for usage stats.
  */
class MongoUserStatsDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils._

  // database collections
  private val futStatsCol: Future[BSONCollection] = db map (_ collection "userstats")
  private val futImportsCol: Future[BSONCollection] = db map (_ collection "imports")
  private val futEntriesCol: Future[BSONCollection] = db map (_ collection "entries")

  // `imports` collection fields
  private val U_ID = "_id" // yes, the "_id" field in the `imports` collection is truly a user UUID, not an ObjectId
  private val IMPT = "imports"

  // `userstats` collection fields
  private val USER = "user" // note this is not the same as Mark.USR which is equal to "userId"
  private val TIME = "time"

  // ensure the mongo collection has the proper indexes
  private val indxs: Map[String, Index] =
    Map(Index(USER -> Ascending :: TIME -> Ascending :: Nil) % s"bin-$USER-1-$TIME-1")
  futStatsCol map (_.indexesManager ensure indxs)

  /** Adds a timestamp record for the user. */
  def punch(userId: UUID): Future[Unit] = for {
    c <- futStatsCol
    wr <- c.insert(d :~ USER -> userId.toString :~ TIME -> DateTime.now.getMillis)
    _ <- wr.failIfError
  } yield ()

  /** Increments user's imports count by `n`. */
  def imprt(userId: UUID, n: Int): Future[Unit] = for {
    c <- futImportsCol
    wr <- c.update(d :~ U_ID -> userId.toString, d :~ "$inc" -> (d :~ IMPT -> n), upsert = true)
    _ <- wr.failIfError
  } yield ()

  // number of weeks over which to compute user statistics
  val nWeeks = 4

  /** Retrieves user's usage stats. */
  // TODO: what is the meaning of offsetMinutes????
  def stats(userId: UUID, offsetMinutes: Int): Future[UserStats] = for {
    cS <- futStatsCol
    cI <- futImportsCol
    cE <- futEntriesCol
    marks <- cE.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
    imports <- cI.find(d :~ U_ID -> userId.toString).one[BSONDocument]

    // count total number of times user added a mark
    total <- cS.count(Some(d :~ USER -> userId.toString))

    // query all records in past four weeks, correcting for user's current timezone
    sel = d :~ USER -> userId.toString :~ TIME -> (d :~ "$gt" -> DateTime.now.minusWeeks(nWeeks).getMillis)
    seq <- cS.find(sel).projection(d :~ TIME -> 1).coll[BSONDocument, Seq]()

  } yield {
    val extraDays = nWeeks * 7 - 1
    val extraOffset = 60 * 24 * extraDays // = 38880
    val firstDay = DateTime.now.minusMinutes(offsetMinutes + extraOffset)

    // group timestamps into collections by day string and take the number of records for each day
    val format = "dd MMM"
    val values: Map[String, Int] = seq groupBy { d =>
      (new DateTime(d.getAs[Long](TIME).get) minusMinutes offsetMinutes).toString(format)
    } mapValues (_.size) withDefaultValue 0

    // get last 28 dates in user's timezone and pair them with numbers of marks
    val days: Seq[UserStatsDay] = for (i <- 0 to extraDays) yield {
      val s = firstDay plusDays i toString format
      UserStatsDay(s, values(s))
    }

    val imported = imports flatMap (_.getAs[Int](IMPT)) getOrElse 0
    UserStats(marks, imported, total, days, (0 /: days) (_ + _.marks), days.reverse maxBy (_.marks))
  }
}
