/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.{Inject, Injector, Singleton}
import com.hamstoo.models._
import com.hamstoo.stream.{CallingUserId, Clock, Datum, injectorly}
import com.hamstoo.stream.config.StreamModule
import com.hamstoo.stream.facet.UserSimilarityOpt
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
  * Data access object for usage stats.
  */
@Singleton
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
    * @param tzOffset  Offset minutes from UTC, obtained from user's HTTP request.  Note that if user is in the
    *                  UTC-4 timezone (e.g. EDT), this value will be +240, not -240, which is why we subtract it below.
    */
  def profileDots(userId: UUID, tzOffset: Int, appInjector: Injector): Future[ProfileDots] = {

    val clockBegin = DateTime.now.minusDays(N_WEEKS * 7 + 1).getMillis
    logger.info(s"Computing profile dots for user $userId with timezone offset $tzOffset minutes since ${clockBegin.tfmt}")

    // bind some stuff in addition to what's required by StreamModule
    implicit val streamInjector = appInjector.createChildInjector(new StreamModule {
      override def configure(): Unit = {
        super.configure()
        CallingUserId := Some(userId)
        Clock.BeginOptional() := clockBegin
      }
    })

    type SrcType = Source[Datum[Option[Double]], NotUsed]
    val src: SrcType = injectorly[UserSimilarityOpt].out.mapConcat(identity) // flatten Data down to individual Datum
    val fut = src.runWith(Sink.seq)(injectorly[Materializer])
    injectorly[Clock].start()

    for {
      cI <- importsColl()
      cE <- marksColl()
      nUserTotalMarks <- cE.count(Some(d :~ Mark.USR -> userId.toString :~
                                            Mark.REF -> (d :~ "$exists" -> false) :~
                                            curnt))
      imports <- cI.find(d :~ U_ID -> userId.toString).one[BSONDocument]

      mbUserStats <- retrieve(userId)
      usims <- fut

    } yield {
      val extraDays = N_WEEKS * 7 - 1
      val extraMinutes = 60 * 24 * extraDays // = 38880
      val firstDay = DateTime.now.minusMinutes(tzOffset + extraMinutes)

      // group timestamps into collections by day string
      val format = "MMMM d" // `format` must not include any part of datetime smaller than day
      val groupedDays = usims.groupBy(d => new DateTime(d.sourceTime).minusMinutes(tzOffset).toString(format))

      // number of records for each day
      val nPerDay: Map[String, Int] = groupedDays.mapValues(_.size).withDefaultValue(0)

      // similarity for each day
      import UserStats.DEFAULT_SIMILARITY
      val similarityByDay: Map[String, Double] = groupedDays.mapValues { sims =>
        import com.hamstoo.models.Representation.VecFunctions
        sims.flatMap(_.value).mean.coalesce(DEFAULT_SIMILARITY)
      }.withDefaultValue(DEFAULT_SIMILARITY)

      // get last 28 dates in user's timezone and pair them with numbers of marks
      val days0: Seq[ProfileDot] = for (i <- 0 to extraDays) yield {
        val dt = firstDay.plusDays(i)
        val str = dt.toString(format)
        ProfileDot(str, dt.getYear, nPerDay(str), userVecSimilarity = similarityByDay(str))
      }

      // don't use `similarityByDay` to compute this in case it includes more days than `days0` does; we really do want
      // to be filtering for DEFAULT_SIMILARITY, not for nMarks == 0, b/c the former can occur for other reasons
      val nonZeroSimilarities0 = days0.map(_.userVecSimilarity).filter(_ !~= DEFAULT_SIMILARITY)

      // correct for the bias that days with fewer marks will have more extreme similarity values
      val (days, nonZeroSimilarities) = if (nonZeroSimilarities0.isEmpty) (days0, nonZeroSimilarities0) else {

        import com.hamstoo.models.Representation.VecFunctions
        val mu = nonZeroSimilarities0.mean
        logger.info(f"nonZeroSimilarities0.mean = $mu%.2f")

        val days1 = days0.map { x => if (x.userVecSimilarity ~= DEFAULT_SIMILARITY) x else {

          val correctedSim = {

            // adjust differences from the mean by n, i.e. correct for the bias that smaller n will have larger diffs
            // (see hamstoo/docs/profileDots_n_vs_similarity.xlsx for construction of this model)
            val adj = 0.342 / math.sqrt((if (x.nMarks < 1) 1 else x.nMarks).toDouble)
            val diff = x.userVecSimilarity - mu
            val abs = math.abs(diff)

            // effectively we're multiplying `abs * sqrt(n)` here, increasing the abs difference from mean for large n
            // 0.5 => correct only by half
            val correctedAbs = math.min(2.0, abs / adj * 0.5 + abs * 0.5)

            // put Humpty Dumpty back together again
            val sign = if (diff < 0) -1 else 1
            logger.info(f"${x.date} (n=${x.nMarks}): ${x.userVecSimilarity}%.2f -> $abs%.2f * ${correctedAbs / abs}%.1f = $correctedAbs%.2f -> ${correctedAbs * sign + mu}%.2f")
            correctedAbs * sign + mu
          }

          x.copy(userVecSimilarity = correctedSim)
        }}

        (days1, days1.map(_.userVecSimilarity).filter(_ !~= DEFAULT_SIMILARITY))
      }

      val nImported = imports flatMap (_.getAs[Int](IMPT)) getOrElse 0
      ProfileDots(nUserTotalMarks,
                  nImported,
                  days,
                  (0 /: days)(_ + _.nMarks),
                  days.reverse.maxBy(_.nMarks),
                  userVecSimMin = Try(nonZeroSimilarities.min).getOrElse(DEFAULT_SIMILARITY),
                  userVecSimMax = Try(nonZeroSimilarities.max).getOrElse(DEFAULT_SIMILARITY),
                  autoGenKws = mbUserStats.flatMap(_.autoGenKws).map(_.mkString(", ")),
                  confirmatoryKws = mbUserStats.flatMap(_.confirmatoryKws).map(_.mkString(", ")),
                  antiConfirmatoryKws = mbUserStats.flatMap(_.antiConfirmatoryKws).map(_.mkString(", ")))
    }
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

  /** Insert a new UserStats. */
  def insert(ustats: UserStats): Future[Unit] = for {
    c <- userstatsColl()
    _ = logger.info(s"Inserting: $ustats")
    wr <- c.insert(ustats)
    _ <- wr.failIfError
  } yield logger.debug(s"Successfully inserted: $ustats")
}
