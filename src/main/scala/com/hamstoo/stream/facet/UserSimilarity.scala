/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.UserStatDao
import com.hamstoo.models.Representation.{Vec, VecEnum}
import com.hamstoo.models.{Representation, UserStats}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.stream.Data.Data
import com.hamstoo.stream.{CallingUserId, DataStream, Datum}
import com.hamstoo.stream.dataset.{ReprQueryResult, RepredMarks, ReprsPair}

import scala.concurrent.Future

/**
  * Similarity of user's typical marked content (from UserStats) to a history of `repredMarks`.
  * @param mbUserId  Using CallingUserId here because it's the same "calling" user, not "search" user, as used
  *                  during search, but to compute this facet the ID cannot be None.
  */
private class UserSimilarityBase(vectorGetter: UserStats => Map[Representation.VecEnum.Value, Vec])
                                (implicit mbUserId: CallingUserId.typ,
                                 repredMarks: RepredMarks,
                                 userStatDao: UserStatDao,
                                 mat: Materializer)
    extends DataStream[Option[Double]] {

  // transform UserStats into Map[String, Vec]s
  private val fmbUserStats = mbUserId.fold(Future.successful(Option.empty[UserStats]))(userStatDao.retrieve)
  private val fmbUserVecs = fmbUserStats.map(_.map(vectorGetter))

  override val in: SourceType = repredMarks()
    .mapAsync(4) { d: Data[RepredMarks.typ] =>
      Future.sequence {
        d.map { e: Datum[RepredMarks.typ] =>

          // unpack the pair datum
          val (mark, ReprsPair(page, user)) = e.value

          // generate a single user similarity from the user's (future) vecs (which should already be complete by now)
          fmbUserVecs.map {
            _.fold(e.withValue(Option.empty[Double])) { uvecs =>

              // get external content (web *page*) Representation vector or, if missing, user-content Representation vec
              val mbVec = page.mbR.flatMap(_.vectors.get(VecEnum.IDF.toString))
                  .orElse(user.mbR.flatMap(_.vectors.get(VecEnum.IDF.toString)))

              // use documentSimilarity rather than IDF-cosine to get a more general sense of the similarity to the user
              e.withValue(mbVec.map(v => VectorEmbeddingsService.documentSimilarity(v, uvecs)))
            }
          }
        }
      }

    }//.map(_.flatten) // do NOT remove Nones; UserStatsDao.profileDots needs them to generate proper daily mark counts
      .asInstanceOf[SourceType] // see "BIG NOTE" on JoinWithable
}


/**
  * UserSimilarity as Option[Double]s, for use by UserStatDao.profileDots, for proper counting of recent marks.
  */
@Singleton
class UserSimilarityOpt @Inject()(implicit @Named(CallingUserId.name) mbUserId: CallingUserId.typ,
                                  repredMarks: RepredMarks,
                                  userStatDao: UserStatDao,
                                  mat: Materializer) extends DataStream[Option[Double]] {

  private val base = new UserSimilarityBase(_.vectors.map(kv => VecEnum.withName(kv._1) -> kv._2))
  override val in: SourceType = base.out
}


/**
  * Flatten Option[Doubles] down to just Doubles (i.e. remove Nones).
  */
@Singleton
class UserSimilarity @Inject()(userSimilarityOpt: UserSimilarityOpt)
                              (implicit mat: Materializer) extends DataStream[Double] {

  import com.hamstoo.stream.StreamDSL._
  override val in: SourceType = userSimilarityOpt.flatten.out

  // here's another way to implement the above line of code, but if you ever catch yourself operating on raw Akka
  // Streams (not DataStreams) and their Data[_]s that's a good sign you're probably doing something wrong (or
  // there's something missing from StreamDSL)
  //override val in: SourceType = userSimilarityOpt().map { dat: Data[_] =>
  //  dat.filter(_.value.isDefined).map(x => x.withValue(x.value.get)) // filter out Nones
  //}
}


/**
  * Confirmation Bias facet is the difference between the similarity to (a vector derived from) a
  * user's confirmatory keywords minus the similarity to (a vector derived from) that user's
  * anti-confirmatory keywords.
  */
@Singleton
class ConfirmationBias @Inject()(implicit @Named(CallingUserId.name) mbUserId: CallingUserId.typ,
                                 repredMarks: RepredMarks,
                                 userStatDao: UserStatDao,
                                 mat: Materializer) extends DataStream[Double] {

  // Option[Double] similarities for each of the rating-weighted (RWT) user vectors
  private val confirmatoryOpt :: antiConfirmatoryOpt :: Nil =
    Seq(VecEnum.RWT, VecEnum.RWTa).map { enumVal =>

      // map to IDF vectors because those are what are used in VectorEmbeddingsService.documentSimilarity
      new UserSimilarityBase(_.vectors.collect { case (k, v) if k == enumVal.toString => VecEnum.IDF -> v })
    }

  import com.hamstoo.stream.StreamDSL._

  // filter out Nones (a.k.a. flatten the DataStreams)
  private val confirmatory :: antiConfirmatory :: Nil: Seq[DataStream[Double]] =
    Seq(confirmatoryOpt, antiConfirmatoryOpt).map(_.flatten)

  override val in: SourceType = (confirmatory - antiConfirmatory).out
}


/**
  * Endowment Bias facet is the difference between the similarity to (a vector derived from) a
  * user's own content confirmatory keywords minus the similarity to (a vector derived from) that user's
  * anti-confirmatory keywords.
  */
@Singleton
class EndowmentBias @Inject()(implicit @Named(CallingUserId.name) mbUserId: CallingUserId.typ,
                                 repredMarks: RepredMarks,
                                 userStatDao: UserStatDao,
                                 mat: Materializer) extends DataStream[Double] {

  // Option[Double] similarities for each of the rating-weighted (RWT) user vectors
  private val confirmatoryOpt :: antiConfirmatoryOpt :: Nil =
    Seq(VecEnum.RWT, VecEnum.RWTa).map { enumVal =>

      // map to IDF vectors because those are what are used in VectorEmbeddingsService.documentSimilarity
      new UserSimilarityBase(_.vectors.collect { case (k, v) if k == enumVal.toString => VecEnum.IDF -> v })
    }

  import com.hamstoo.stream.StreamDSL._

  // filter out Nones (a.k.a. flatten the DataStreams)
  private val confirmatory :: antiConfirmatory :: Nil: Seq[DataStream[Double]] =
    Seq(confirmatoryOpt, antiConfirmatoryOpt).map(_.flatten)

  override val in: SourceType = (confirmatory - antiConfirmatory).out
}