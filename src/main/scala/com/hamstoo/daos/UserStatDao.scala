/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models._
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for usage stats.
  */
class UserStatDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[UserStatDao])

  // database collections
  private def userstatsColl(): Future[BSONCollection] = db().map(_.collection("userstats2"))
  private def importsColl(): Future[BSONCollection] = db().map(_.collection("imports"))
  private def marksColl(): Future[BSONCollection] = db().map(_.collection("entries"))

  // `imports` collection fields
  private val U_ID = "_id" // yes, the "_id" field in the `imports` collection is truly a user UUID, not an ObjectId
  private val IMPT = "imports"

  /** Increments user's imports count by `n`. */
  def imprt(userId: UUID, n: Int): Future[Unit] = for {
    c <- importsColl()
    wr <- c.update(d :~ U_ID -> userId.toString, d :~ "$inc" -> (d :~ IMPT -> n), upsert = true)
    _ <- wr.failIfError
  } yield ()

  // number of weeks over which to compute user statistics
  val N_WEEKS = 4

  /**
    * Constructs user's profile dots a.k.a. usage stats.
    * @param offsetMinutes  offset minutes from UTC, obtained from user's HTTP request
    */
  def profileDots(userId: UUID, offsetMinutes: Int): Future[ProfileDots] = for {
    cI <- importsColl()
    cE <- marksColl()
    sel0 = d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME
    nMarks <- cE.count(Some(sel0))
    imports <- cI.find(d :~ U_ID -> userId.toString).one[BSONDocument]

    // query all records in past four weeks, correcting for user's current timezone
    sel1 = sel0 :~ Mark.TIMEFROM -> (d :~ "$gt" -> DateTime.now.minusWeeks(N_WEEKS).getMillis) :~
                   Mark.TAGSx -> (d :~ "$not" -> (d :~ "$all" -> Seq(MarkData.IMPORT_TAG)))
    _ = logger.debug(BSONDocument.pretty(sel1))
    seq <- cE.find(sel1).projection(d :~ Mark.TIMEFROM -> 1).coll[BSONDocument, Seq]()

  } yield {
    val extraDays = N_WEEKS * 7 - 1
    val extraOffset = 60 * 24 * extraDays // = 38880
    val firstDay = DateTime.now.minusMinutes(offsetMinutes + extraOffset)

    // group timestamps into collections by day string and take the number of records for each day
    val format = "MMMM d"
    val values: Map[String, Int] = seq groupBy { d =>
      new DateTime(d.getAs[Long](Mark.TIMEFROM).get).minusMinutes(offsetMinutes).toString(format)
    } mapValues (_.size) withDefaultValue 0

    seq.foreach(x => logger.debug(BSONDocument.pretty(x)))

    // get last 28 dates in user's timezone and pair them with numbers of marks
    val days: Seq[ProfileDot] = for (i <- 0 to extraDays) yield {
      val s = firstDay.plusDays(i).toString(format)
      ProfileDot(s, values(s))
    }

    val nImported = imports flatMap (_.getAs[Int](IMPT)) getOrElse 0
    ProfileDots(nMarks, nImported, days, (0 /: days)(_ + _.nMarks), days.reverse.maxBy(_.nMarks))
  }

  import com.hamstoo.models.UserStats._

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: TIMESTAMP -> Ascending :: Nil) % s"bin-$USR-1-$TIMESTAMP-1" ::
    Nil toMap;
  Await.result(userstatsColl().map(_.indexesManager.ensure(indxs)), 93 seconds)

  /** Retrieves most recent UserStats for given user ID. */
  def retrieve(userId: UUID): Future[Option[UserStats]] = {
    logger.debug(s"Retrieving most recent UserStats for user $userId")
    for {
      c <- userstatsColl()
      mb <- c.find(d :~ USR -> userId).sort(d :~ TIMESTAMP -> -1).one[UserStats]
    } yield {
      logger.debug(s"${mb.size} UserStats were successfully retrieved")
      mb
    }
  }
}
