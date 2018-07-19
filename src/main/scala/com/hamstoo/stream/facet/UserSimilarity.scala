/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.UserStatDao
import com.hamstoo.models.Representation.VecEnum
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.stream.Data.Data
import com.hamstoo.stream.{CallingUserId, DataStream, Datum}
import com.hamstoo.stream.dataset.{RepredMarks, ReprsPair}

import scala.concurrent.Future

/**
  * Similarity of user's typical marked content (from UserStats) to a history of `repredMarks`.
  */
@Singleton
class UserSimilarityOpt @Inject()(@Named(CallingUserId.name) callingUserId: CallingUserId.typ,
                                  repredMarks: RepredMarks,
                                  userStatDao: UserStatDao)
                                 (implicit mat: Materializer) extends DataStream[Option[Double]] {

  // transform UserStats into Map[String, Vec]s
  private val fmbUserStats = userStatDao.retrieve(callingUserId)
  private val fmbUserVecs = fmbUserStats.map(_.map(_.vectors.map(kv => VecEnum.withName(kv._1) -> kv._2)))

  override val in: SourceType = repredMarks()
    .mapAsync(4) { d: Data[RepredMarks.typ] =>
      Future.sequence {
        d.map { e: Datum[RepredMarks.typ] =>

          // unpack the pair datum
          val (mark, ReprsPair(pageReprs, userReprs)) = e.value

          // generate a single user similarity from the user's (future) vecs (which should already be complete by now)
          fmbUserVecs.map {
            _.fold(e.withValue(Option.empty[Double])) { uvecs =>

              // get external content (web *page*) Representation vector or, if missing, user-content Representation vec
              val mbVec = pageReprs.headOption.flatMap(_.mbR).flatMap(_.vectors.get(VecEnum.IDF.toString))
                  .orElse(userReprs.headOption.flatMap(_.mbR).flatMap(_.vectors.get(VecEnum.IDF.toString)))

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
  * Flatten Option[Doubles] down to just Doubles.
  */
@Singleton
class UserSimilarity @Inject()(userSimilarityOpt: UserSimilarityOpt)
                              (implicit mat: Materializer) extends DataStream[Double] {

  override val in: SourceType = userSimilarityOpt().map {
    _.flatMap { d: Datum[Option[Double]] =>
      d.value.map(v => d.withValue(v)) // if `d.value` the Option is None then map to an Option.empty[Datum]
    }
  }
}

object UserSimilarity {
  val DEFAULT_ARG = 0.85 // using 0.85 just to keep things interesting
}