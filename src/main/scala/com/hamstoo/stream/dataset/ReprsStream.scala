/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.dataset

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.MongoRepresentationDao
import com.hamstoo.models.{MSearchable, RSearchable}
import com.hamstoo.stream._
import com.hamstoo.utils.ExtendedTimeStamp
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
  * @param mbQuery2Vecs  An optional Query2Vecs used to compute a weighted average repr for each mark if the stream
  *                      of marks was the result of a search.
  * @param marksStream   Representations will be streamed for this stream of marks.
  */
@Singleton
class ReprsStream @Inject() (marksStream: MarksStream,
                             @Named(Query2VecsOptional.name) mbQuery2Vecs: Query2VecsOptional.typ,
                             logLevel: LogLevelOptional.typ)
                            (implicit materializer: Materializer, ec: ExecutionContext,
                             reprDao: MongoRepresentationDao)
    extends DataStream[ReprsPair]() {

  // TODO: change the output of this stream to output EntityId(markId, reprId, reprType, queryWord) 4-tuples

  // set logging level for this ReprsStream *instance*
  // "Note that you can also tell logback to periodically scan your config file"
  // https://stackoverflow.com/questions/3837801/how-to-change-root-logging-level-programmatically
  val logger0: Slf4jLogger = LoggerFactory.getLogger(classOf[ReprsStream].getName.stripSuffix("$"))
  logLevel.foreach { lv => logger0.asInstanceOf[LogbackLogger].setLevel(lv); logger0.info(s"Overriding log level to: $lv") }
  val logger1 = new Logger(logger0)

  /** Maps the stream of marks to their reprs. */
  override val hubSource: Source[Datum[ReprsPair], NotUsed] = marksStream().mapAsync(4) { dat =>

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = mbQuery2Vecs.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val cleanedQuery = mbCleanedQuery.getOrElse(Seq(("", 0)))

    val mark = dat.value
    val primaryReprId = mark.primaryRepr // .getOrElse("") already applied
    val usrContentReprId = mark.userContentRepr.getOrElse("")
    val reprIds = Set(primaryReprId, usrContentReprId).filter(_.nonEmpty)

    // run a separate MongoDB Text Index search over `representations` collection for each query word
    val fscoredReprs = mbQuerySeq.mapOrEmptyFuture(reprDao.search(reprIds, _)).flatMap { seqOfMaps =>

      // if there aren't any query words (or mbQuerySeq.isEmpty) then instead run a simple `retrieve` w/out searching
      if (seqOfMaps.isEmpty) reprDao.retrieve(reprIds).map(oneMap => Seq(oneMap)) else Future.successful(seqOfMaps)
    }

    // also get any reprs that might have been excluded by the above search (we can compute vector similarities
    // to these but we'll have to use the entries/marks collection's Text Index score to rank them)
    // TODO: maybe use the representations collection's Text Index score and drop the marks collection's Text Index?
    val funscoredReprs = reprDao.retrieve(reprIds)

    for(scoredReprs <- fscoredReprs; unscoredReprs <- funscoredReprs) yield {

      // each element of `scoredReprs` contains a collection of representations for the respective word in
      // `cleanedQuery`, so zip them together, pull out the requested reprId, and multiply the MongoDB search
      // scores `dbScore` by the query word counts `q._2`
      def searchTermReprs(rOrU: String, reprId: String): Seq[QueryResult] =

        // both of these must contain at least 1 element
        cleanedQuery.view.zip(scoredReprs).map { case (q, scoredReprsForThisWord) =>

          // only use unscored repr if a scored repr was not found by MongoDB Text Index search
          lazy val mbUnscored = unscoredReprs.get(reprId)
          val mbR = scoredReprsForThisWord.get(reprId).orElse(mbUnscored)
          val dbScore = mbR.flatMap(_.score).getOrElse(0.0)

          def toStr(opt: Option[RSearchable]) = opt.map(x => (x.nWords.getOrElse(0), x.score.fold("NaN")(s => f"$s%.2f")))
          logger1.trace(f"  (\u001b[2m${mark.id}\u001b[0m) $rOrU-db$q: dbScore=$dbScore%.2f reprs=${toStr(scoredReprsForThisWord.get(reprId))}/${toStr(mbUnscored)}")

          QueryResult(q._1, mbR, dbScore, q._2)
        }.force

      val siteReprs = searchTermReprs("R", primaryReprId)    // website-content representations
      val userReprs = searchTermReprs("U", usrContentReprId) //    user-content representations

      // technically we should update knownTime here to the time of repr computation, but it's not really important
      // in this case b/c what we really want is "time that this data could have been known"
      val d = dat.withValue(ReprsPair(siteReprs, userReprs))
      logger1.trace(s"\u001b[32m${dat.id}\u001b[0m: ${dat.knownTime.Gs}")
      d
    }
  }
}

/**
  * Represented marks stream as the representations by themselves aren't that useful, are they?
  */
@Singleton
class RepredMarks @Inject() (marks: MarksStream, reprs: ReprsStream)
                            (implicit materializer: Materializer)
    extends DataStream[(MSearchable, ReprsPair)] {

  import com.hamstoo.stream.Join.JoinWithable
  override def hubSource: SourceType = marks().joinWith(reprs()) { case x => x }.asInstanceOf[SourceType]
}