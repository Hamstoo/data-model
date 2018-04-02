package com.hamstoo.stream

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.MongoRepresentationDao
import com.hamstoo.models.{MSearchable, RSearchable}
import com.hamstoo.utils.{NON_IDS, ObjectId, TimeStamp}
import com.hamstoo.models.Representation.{Vec, VecEnum}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.WordCountsType
import com.hamstoo.stream.MarksStream.{ExtendedQuerySeq, Query2VecsOptional}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.Future

/**
  * A stream of a user's mark's representation vectors.
  * @param marksStream  A stream of marks to have their reprs looked up.
  */
@Singleton
/*case*/ class ReprsStream @Inject()(query2Vecs: Query2VecsOptional,
                                     marksStream: MarksStream,
                                     reprsDao: MongoRepresentationDao)
                                    (implicit clock: Clock, m: Materializer)
    extends DataStream[RSearchable]() {

  var vLevel: Int = ReprsStream.VERBOSE_OFF
  private def logLevel = if (vLevel >= ReprsStream.LOG_DEBUG) logger.info(_: String) else logger.trace(_: String)

  /** Map a stream of marks to their reprs. */
  override val hubSource = marksStream().mapAsync(4) { mark: MSearchable =>



    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = query2Vecs.value.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val cleanedQuery = mbCleanedQuery.getOrElse(Seq.empty[(String, Int)])

    val primaryReprId = mark.primaryRepr // .getOrElse("") already applied
    val usrContentReprId = mark.userContentRepr.filterNot(NON_IDS.contains).getOrElse("")
    val reprIds = Set(primaryReprId, usrContentReprId).filter(_.nonEmpty)

    // run a separate MongoDB Text Index search over `representations` collection for each query word
    val fscoredReprs = mbQuerySeq.mapOrEmptyFuture(reprsDao.search(reprIds, _))

    // also get any user-content reprs that might have been excluded by the above search (we can compute
    // similarities to these but we'll have to use the marks collection's Text Index score)
    // TODO: maybe use the representations collection's Text Index score and drop the marks collection's Text Index?
    val fusrContentRepr = reprsDao.retrieve(usrContentReprId)

    for(scoredReprs <- fscoredReprs; usrContentRepr <- fusrContentRepr) yield {

      case class QueryResult(qword: String, mbR: Option[RSearchable], dbScore: Double, count: Int)

      // each element of `scoredReprs` contains a collection of representations for the respective word in
      // `cleanedQuery`, so zip them together, pull out the requested reprId, and multiply the MongoDB search
      // scores (rawScore) by the query word counts (q._2)
      def searchTermReprs(rOrU: String, reprId: String): Seq[QueryResult] =
        cleanedQuery.view.zip(scoredReprs).map { case (q, rmap) =>
          lazy val ucR = usrContentRepr.filter(_.id == reprId)
          val mbR = rmap.get(reprId).orElse(ucR)

          // rmap will contain MongoDB Text Index search scores, while usrContentReprs will not
          assert(ucR.fold(true)(_.score.isEmpty))
          val dbScore = mbR.flatMap(_.score).getOrElse(0.0)

          def toStr(opt: Option[RSearchable]) = opt.map(x => (x.nWords.getOrElse(0), x.score.fold("NaN")(s => f"$s%.2f")))
          logLevel(f"  $rOrU-db: qword=$q dbScore=$dbScore%.2f reprs=${toStr(rmap.get(reprId))}/${toStr(ucR)}")
          QueryResult(q._1, mbR, dbScore, q._2)
        }.force

      val siteReprs = searchTermReprs("R", primaryReprId)    // website-content reprs
      val userReprs = searchTermReprs("U", usrContentReprId) //    user-content reprs


      // TODO: compute aggregated reprs (searchTerms2Scores)



    }

    ()
/*
    // get the mark's primaryRepr and map its PC1 vector to a Datum
      reprsDao.retrieve(mark.primaryRepr).map {
        _.flatMap { repr =>
          repr.vectors.get(VecEnum.PC1.toString).map { vec =>
            Datum(MarkId(mark.id), mark.timeFrom, vec)
          }
        }
      }
    }.collect { case Some(z) => z } // remove Nones (flatten doesn't appear to exist)
      .runWith(Sink.seq) // materialize to Iterable
*/
  }

}

object ReprsStream {

  val logger = Logger(classOf[ReprsStream])

  // verbosity levels
  val VERBOSE_OFF = 0
  val DISPLAY_SCORES = 1
  val LOG_DEBUG = 2
}
