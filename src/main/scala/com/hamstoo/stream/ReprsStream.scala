/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.MongoRepresentationDao
import com.hamstoo.models.RSearchable
import com.hamstoo.stream.MarksStream.ExtendedQuerySeq
import ch.qos.logback.classic.{Logger => LogbackLogger}
import org.slf4j.{LoggerFactory, Logger => Slf4jLogger}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}


/**
  * A MongoDB Text Index search score for a search term / query word along with its corresponding repr.
  */
case class QueryResult(qword: String, mbR: Option[RSearchable], dbScore: Double, count: Int)

/**
  * The instance type streamed from the ReprsStream.
  * @param siteReprs  A representation corresponding to the (external) content of the marked site.
  * @param userReprs  A representation constructed from the user-created content (comments, labels, highlights, notes).
  */
case class ReprsPair(siteReprs: Seq[QueryResult], userReprs: Seq[QueryResult])

/**
  * A stream of a user's marks' representations.
  * 
  * @param query2Vecs    An optional Query2Vecs used to compute a weighted average repr for each mark if the stream
  *                      of marks was the result of a search.
  * @param marksStream   Representations will be streamed for this stream of marks.
  */
@Singleton
/*case*/ class ReprsStream @Inject()(marksStream: MarksStream,
                                     query2Vecs: Query2VecsOptional,
                                     logLevel: LogLevelOptional)
                                    (implicit materializer: Materializer, ec: ExecutionContext,
                                     reprDao: MongoRepresentationDao)
    extends DataStream[ReprsPair]() {

  // TODO: change the output of this stream to output EntityId(markId, reprId, reprType, queryWord) 4-tuples

  // set logging level for this ReprsStream *instance*
  // "Note that you can also tell logback to periodically scan your config file"
  // https://stackoverflow.com/questions/3837801/how-to-change-root-logging-level-programmatically
  val logger0: Slf4jLogger = LoggerFactory.getLogger(classOf[ReprsStream].getName.stripSuffix("$"))
  logger0.asInstanceOf[LogbackLogger].setLevel(logLevel.value)
  override val logger = new Logger(logger0)

  /** Maps the stream of marks to their reprs. */
  override val hubSource: Source[Data[ReprsPair], NotUsed] = marksStream().mapAsync(4) { dat =>

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = query2Vecs.value.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val cleanedQuery = mbCleanedQuery.getOrElse(Seq(("", 0)))

    val mark = dat.oval.get.value
    val primaryReprId = mark.primaryRepr // .getOrElse("") already applied
    val usrContentReprId = mark.userContentRepr.getOrElse("")
    val reprIds = Set(primaryReprId, usrContentReprId).filter(_.nonEmpty)

    // run a separate MongoDB Text Index search over `representations` collection for each query word
    val fscoredReprs = mbQuerySeq.mapOrEmptyFuture(reprDao.search(reprIds, _)).flatMap { seqOfMaps =>

      // if there aren't any query words (or mbQuerySeq.isEmpty) then instead run a simple `retrieve` w/out searching
      if (seqOfMaps.isEmpty) reprDao.retrieve(reprIds).map(oneMap => Seq(oneMap)) else Future.successful(seqOfMaps)
    }

    // also get any user-content reprs that might have been excluded by the above search (we can compute
    // similarities to these but we'll have to use the marks collection's Text Index score to rank them)
    // TODO: maybe use the representations collection's Text Index score and drop the marks collection's Text Index?
    val funscoredUsrContentRepr = reprDao.retrieve(usrContentReprId)

    for(scoredReprs <- fscoredReprs; unscoredUsrContentRepr <- funscoredUsrContentRepr) yield {

      // each element of `scoredReprs` contains a collection of representations for the respective word in
      // `cleanedQuery`, so zip them together, pull out the requested reprId, and multiply the MongoDB search
      // scores `dbScore` by the query word counts `q._2`
      def searchTermReprs(rOrU: String, reprId: String): Seq[QueryResult] =

        // both of these must contain at least 1 element
        cleanedQuery.view.zip(scoredReprs).map { case (q, scoredReprsForThisWord) =>

          // only use unscoredUsrContentRepr if a scored user-content repr was not found by MongoDB Text Index search
          lazy val ucR = unscoredUsrContentRepr.filter(_.id == reprId)
          val mbR = scoredReprsForThisWord.get(reprId).orElse(ucR)
          val dbScore = mbR.flatMap(_.score).getOrElse(0.0)

          def toStr(opt: Option[RSearchable]) = opt.map(x => (x.nWords.getOrElse(0), x.score.fold("NaN")(s => f"$s%.2f")))
          logger.trace(f"  $rOrU-db: qword=$q dbScore=$dbScore%.2f reprs=${toStr(scoredReprsForThisWord.get(reprId))}/${toStr(ucR)}")

          QueryResult(q._1, mbR, dbScore, q._2)
        }.force

      val siteReprs = searchTermReprs("R", primaryReprId)    // website-content representations
      val userReprs = searchTermReprs("U", usrContentReprId) //    user-content representations

      Datum(dat.oid.get, dat.knownTime, ReprsPair(siteReprs, userReprs))
    }
  }
}
