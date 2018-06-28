/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.{Inject, Injector, Singleton}
import com.hamstoo.models.Representation.{Vec, VecEnum}
import com.hamstoo.models._
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.stream.{CallingUserId, Clock, Datum, injectorly}
import com.hamstoo.stream.config.StreamModule
import com.hamstoo.stream.dataset.{MarksStream, ReprsPair, ReprsStream}
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
        CallingUserId := userId
        Clock.BeginOptional() := clockBegin
      }
    })

    val marks = injectorly[MarksStream]
    val reprs = injectorly[ReprsStream]

    // TODO: this should be possible to implement via the StreamDSL?  would need a UserStatsStream of course

    // get external content (web *site*) Representation vector or, if missing, user-content Representation vector
    val rsource: Source[(TimeStamp, Vec), NotUsed] = reprs.out.mapConcat { _.flatMap { x: Datum[ReprsPair] =>
      val mbVec = x.value.siteReprs.headOption.flatMap(_.mbR).flatMap(_.vectors.get(VecEnum.IDF.toString))
        .orElse(x.value.userReprs.headOption.flatMap(_.mbR).flatMap(_.vectors.get(VecEnum.IDF.toString)))
      mbVec.map(x.sourceTime -> _)
    }}

    // don't rely on reprs being populated for all marks
    val msource: Source[TimeStamp, NotUsed] = marks.out.mapConcat(_.map(_.sourceTime))

    // TODO: implement a test that ensures reprs' timestamps match marks'

    val mfut = msource.runWith(Sink.seq)(injectorly[Materializer])
    val rfut = rsource.runWith(Sink.seq)(injectorly[Materializer])
    injectorly[Clock].start()

    for {
      cI <- importsColl()
      cE <- marksColl()
      nUserTotalMarks <- cE.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
      imports <- cI.find(d :~ U_ID -> userId.toString).one[BSONDocument]

      mbUserStats <- retrieve(userId)
      marks <- mfut
      reprs <- rfut

    } yield {
      val extraDays = N_WEEKS * 7 - 1
      val extraMinutes = 60 * 24 * extraDays // = 38880
      val firstDay = DateTime.now.minusMinutes(tzOffset + extraMinutes)

      // group timestamps into collections by day string
      val format = "MMMM d" // `format` must not include any part of datetime smaller than day
      val groupedDays = marks.groupBy(new DateTime(_).minusMinutes(tzOffset).toString(format))

      // number of records for each day
      val nPerDay: Map[String, Int] = groupedDays.mapValues(_.size).withDefaultValue(0)

      // similarity for each day
      import UserStats.DEFAULT_SIMILARITY
      val mbUserVecs = mbUserStats.map(_.vectors.map(kv => VecEnum.withName(kv._1) -> kv._2))
      val similarityByDay: Map[String, Double] = mbUserVecs.fold(Map.empty[String, Double]) { uvecs =>
        val mappedReprs = reprs.toMap
        groupedDays.mapValues { timestamps =>
          import com.hamstoo.models.Representation.VecFunctions
          val meanSimilarity = timestamps.flatMap {

            // use documentSimilarity rather than IDF-cosine to get a more general sense of the similarity to the user
            mappedReprs.get(_).map(v => VectorEmbeddingsService.documentSimilarity(v, uvecs)) }.mean
            //mappedReprs.get(_).flatMap(v => uvecs.get(VecEnum.IDF).map(_ cosine v)) }.mean

          if (meanSimilarity.isNaN) DEFAULT_SIMILARITY else meanSimilarity
        }
      }.withDefaultValue(DEFAULT_SIMILARITY)

      // get last 28 dates in user's timezone and pair them with numbers of marks
      val days: Seq[ProfileDot] = for (i <- 0 to extraDays) yield {
        val dt = firstDay.plusDays(i).toString(format)
        ProfileDot(dt, nPerDay(dt), userVecSimilarity = similarityByDay(dt))
      }

      // don't use `similarityByDay` to compute this in case it includes more days than `days` does
      val nonZeroSimilarities = days.map(_.userVecSimilarity).filter(_ !~= 0.0)

      val nImported = imports flatMap (_.getAs[Int](IMPT)) getOrElse 0
      ProfileDots(nUserTotalMarks,
                  nImported,
                  days,
                  (0 /: days)(_ + _.nMarks),
                  days.reverse.maxBy(_.nMarks),
                  userVecSimMin = Try(nonZeroSimilarities.min).getOrElse(DEFAULT_SIMILARITY),
                  userVecSimMax = Try(nonZeroSimilarities.max).getOrElse(DEFAULT_SIMILARITY),
                  autoGenKws = mbUserStats.flatMap(_.autoGenKws).map(_.mkString(", ")))
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
