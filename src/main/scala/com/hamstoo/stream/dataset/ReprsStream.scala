/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.dataset

import akka.stream.Materializer
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.RepresentationDao
import com.hamstoo.models.{Mark, RSearchable}
import com.hamstoo.stream.Data.ExtendedData
import com.hamstoo.stream._
import com.hamstoo.utils.{ExtendedTimeStamp, TimeStamp}
import org.slf4j.LoggerFactory
import play.api.Logger

import scala.concurrent.Future


/**
  * A MongoDB Text Index search score for a search term / query word.
  */
case class ReprQueryWord(qword: String, dbScore: Double, count: Int)

/**
  * A repr with a list of search terms / query words.
  * @param mbR    Optional repr with score == None.  MongoDB Text Index search scores have been moved into `words`.
  * @param words  Parallel to mbQuery2Vecs, one repr search result for each query word.
  */
case class ReprQueryResult(mbR: Option[RSearchable], words: Seq[ReprQueryWord])

/**
  * The instance type streamed from the ReprsStream.
  * @param page  A representation corresponding to the (external) content of the marked page.
  * @param user  A representation constructed from the user-created content (comments, labels, highlights, notes).
  */
case class ReprsPair(page: ReprQueryResult, user: ReprQueryResult)

/**
  * A stream of a user's marks' representations.
  * 
  * @param mbQuery2Vecs  An optional Query2Vecs used to compute a weighted average repr for each mark if the stream
  *                      of marks was the result of a search.
  * @param marksStream   Representations will be streamed for this stream of marks.
  */
@Singleton
class ReprsStream @Inject()(marksStream: MarksStream,
                            @Named(Query2Vecs.name) mbQuery2Vecs: Query2Vecs.typ,
                            logLevel: LogLevelOptional)
                           (implicit clock: Clock,
                            mat: Materializer,
                            reprDao: RepresentationDao)
    extends PreloadObserver[Mark, ReprsPair](subject = marksStream) {

  // TODO: change the output of this stream to output EntityId(markId, reprId, reprType, queryWord) 4-tuples

  // set logging level for this ReprsStream *instance* (change prefix w/ "I" to prevent modifying the other logger)
  // "Note that you can also tell logback to periodically scan your config file"
  // https://stackoverflow.com/questions/3837801/how-to-change-root-logging-level-programmatically
  val loggerI: Logger = {
    val logback = LoggerFactory.getLogger("I" + classOf[ReprsStream].getName).asInstanceOf[LogbackLogger]
    logLevel.value.filter(_ != logback.getLevel).foreach { lv => logback.setLevel(lv); logback.debug(s"Overriding log level to: $lv") }
    new Logger(logback)
  }

  // unpack query words/counts/vecs (which there may none of)
  private lazy val mbCleanedQuery = mbQuery2Vecs.map(_._1)
  private lazy val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
  private lazy val cleanedQuery = mbCleanedQuery.getOrElse(Seq(("", 0)))

  /** Maps the stream of marks to their reprs. */
  override def observerPreload(fSubjectData: PreloadType[Mark], begin: TimeStamp, end: TimeStamp):
                                                                                        PreloadType[ReprsPair] = {
    fSubjectData.flatMap { subjectData =>

      val marks = subjectData.map(_.value)
      val primaryReprIds = marks.map(_.primaryRepr) // .getOrElse("") already applied
      val usrContentReprIds = marks.map(_.userContentRepr.getOrElse(""))
      val reprIds = (primaryReprIds ++ usrContentReprIds).filter(_.nonEmpty).toSet

      //val approxBegin = if (marks.isEmpty) 0L else marks.map(_.timeFrom).min
      //val approxEnd   = if (marks.isEmpty) 0L else marks.map(_.timeFrom).max
      logger.debug(s"[3] Performing observerPreload between ${begin.tfmt} and ${end.tfmt} for ${marks.size} marks, ${mbQuerySeq.map(_.size)} search terms, ${primaryReprIds.size} primaryReprIds, ${usrContentReprIds.size} usrContentReprIds, ${reprIds.size} reprIds, and with ${Runtime.getRuntime.availableProcessors} available processors")

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
        logger.debug(s"[3.1] observerPreload: nScoredReprs = ${scoredReprs.map(_.size).sum}, nUnscoredReprs = ${unscoredReprs.size}") // debugging timeout
        subjectData.map { dat =>

          val mark = dat.value
          val primaryReprId = mark.primaryRepr
          val usrContentReprId = mark.userContentRepr.getOrElse("")

          // each element of `scoredReprs` contains a collection of representations for the respective word in
          // `cleanedQuery`, so zip them together, pull out the requested reprId, and multiply the MongoDB search
          // scores `dbScore` by the query word counts `q._2`
          def searchTermReprs(pOrU: String, reprId: String): ReprQueryResult = {

            var mbR: Option[RSearchable] = None

            // both of these must contain at least 1 element
            val words = cleanedQuery.view.zip(scoredReprs).map { case (q, scoredReprsForThisWord) =>

              // only use unscored repr if a scored repr was not found by MongoDB Text Index search
              lazy val mbUnscored = unscoredReprs.get(reprId)
              val mbR_i = scoredReprsForThisWord.get(reprId).orElse(mbUnscored)
              val dbScore = mbR_i.flatMap(_.score).getOrElse(0.0)

              // it doesn't matter which repr we choose, all of them will have the same reprId and vectors, just with
              // different MongoDB Text Index search scores, which is why we're inside this function to begin with
              if (mbR.isEmpty && mbR_i.isDefined)
                mbR = mbR_i.map(_.xcopy(score = None))

              def toStr(opt: Option[RSearchable]) = opt.map(x => (x.nWords.getOrElse(0), x.score.fold("NaN")(s => f"$s%.2f")))
              loggerI.trace(f"  (\u001b[2m${mark.id}\u001b[0m) $pOrU-db$q: dbScore=$dbScore%.2f reprs=${toStr(scoredReprsForThisWord.get(reprId))}/${toStr(mbUnscored)}")

              ReprQueryWord(q._1, dbScore, q._2)
            }.force

            ReprQueryResult(mbR, words)
          }

          val pageQueryResult = searchTermReprs("P", primaryReprId)    // webpage-content representations
          val userQueryResult = searchTermReprs("U", usrContentReprId) //    user-content representations

          // technically we should update knownTime here to the time of repr computation, but it's not really important
          // in this case b/c what we really want is "time that this data could have been known"
          val d = dat.withValue(ReprsPair(pageQueryResult, userQueryResult))
          loggerI.trace(s"\u001b[32m${dat.id}\u001b[0m: ${dat.knownTime.Gs}")
          d
        }
      }
    }
  }
}

/**
  * Represented marks stream as the representations by themselves aren't that useful, are they?
  */
@Singleton
class RepredMarks @Inject()(marks: MarksStream, reprs: ReprsStream)
                           (implicit mat: Materializer)
    extends DataStream[RepredMarks.typ] {

  import com.hamstoo.stream.Join.JoinWithable

  // TODO: implement a test that ensures reprs' timestamps match marks'
  override val in: SourceType = marks().joinWith(reprs()) { case x => x }
    .asInstanceOf[SourceType] // see comment on JoinWithable as to why this cast is necessary
    .map { d => logger.debug(s"${d.sourceTimeMax.tfmt} (n=${d.size})"); d }
}

object RepredMarks {
  type typ = (Mark, ReprsPair)
}