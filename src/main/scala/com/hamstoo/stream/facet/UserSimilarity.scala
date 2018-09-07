/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.UserStatDao
import com.hamstoo.models.Representation.{Vec, VecEnum}
import com.hamstoo.models.{RSearchable, Representation, UserStats}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.stream.Data.Data
import com.hamstoo.stream.{CallingUserId, DataStream}
import com.hamstoo.stream.dataset.{ReprsPair, ReprsStream}

import scala.concurrent.Future

object UserSimilarity {

  type VectorGetterType = UserStats => Map[Representation.VecEnum.Value, Vec]

  /** Default vectorGetter parameter value for UserSimilarityBase. */
  val DEFAULT_VECTOR_GETTER: VectorGetterType = _.vectors.map(kv => VecEnum.withName(kv._1) -> kv._2)

  type VectorOpType = (Option[RSearchable],
                       Option[RSearchable],
                       Map[Representation.VecEnum.Value, Vec]) => Option[Double]

  /** Default vectorOp parameter value for UserSimilarityBase. */
  val DEFAULT_VECTOR_OP: VectorOpType = (mbPageRepr: Option[RSearchable],
                                         mbUserContentRepr: Option[RSearchable],
                                         userVecs: Map[Representation.VecEnum.Value, Vec]) => {

    // get external content (web *page*) Representation vector or, if missing, user-content Representation vec
    val mbVec =      mbPageRepr.flatMap(_.vectors.get(VecEnum.IDF.toString))
      .orElse(mbUserContentRepr.flatMap(_.vectors.get(VecEnum.IDF.toString)))

    // use documentSimilarity rather than IDF-cosine to get a more general sense of the similarity to the user
    mbVec.map(v => VectorEmbeddingsService.documentSimilarity(v, userVecs))
  }
}

/**
  * Similarity of user's typical marked content (from UserStats) to a history of `repredMarks`.
  * @param mbUserId  Using CallingUserId here because it's the same "calling" user, not "search" user, as used
  *                  during search, but to compute this facet the ID cannot be None.
  */
private class UserSimilarityBase(vectorGetter: UserSimilarity.VectorGetterType = UserSimilarity.DEFAULT_VECTOR_GETTER,
                                 vectorOp: UserSimilarity.VectorOpType = UserSimilarity.DEFAULT_VECTOR_OP)
                                (implicit mbUserId: CallingUserId.typ,
                                 reprs: ReprsStream,
                                 userStatDao: UserStatDao,
                                 mat: Materializer)
    extends DataStream[Option[Double]] {

  // transform UserStats into Map[String, Vec]s
  private val fmbUserStats = mbUserId.fold(Future.successful(Option.empty[UserStats]))(userStatDao.retrieve)
  private val fmbUserVecs = fmbUserStats.map(_.map(vectorGetter))

  import com.hamstoo.stream.StreamDSL._

  override val in: SourceType = reprs.map { case ReprsPair(page, user) =>

    // generate a single user similarity from the user's (future) vecs (which should already be complete by now)
    fmbUserVecs.map { _.fold(Option.empty[Double])(vectorOp(page.mbR, user.mbR, _)) }
  }
    .out

    // all of this to flatten the Future (i.e. "pivot" from Seq[Datum[Future]] to Future[Seq[Datum]])
    // TODO: add this to StreamDSL (can probably just overload `flatten` or call it `flattenAsync(4)`)
    .mapAsync(4) { dat: Data[Future[Option[Double]]] => Future.sequence(dat.map(x => x.value.map(x.withValue))) }

    .asInstanceOf[SourceType] // see "BIG NOTE" on JoinWithable
}


/**
  * UserSimilarity as Option[Double]s, for use by UserStatDao.profileDots, for proper counting of recent marks.
  */
@Singleton
class UserSimilarityOpt @Inject()(implicit @Named(CallingUserId.name) mbUserId: CallingUserId.typ,
                                  reprs: ReprsStream,
                                  userStatDao: UserStatDao,
                                  mat: Materializer) extends DataStream[Option[Double]] {

  private val base = new UserSimilarityBase()
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
                                 reprs: ReprsStream,
                                 userStatDao: UserStatDao,
                                 mat: Materializer) extends DataStream[Double] {

  // Option[Double] similarities for each of the rating-weighted (RWT) user vectors
  private val confirmatoryOpt :: antiConfirmatoryOpt :: Nil =
    Seq(VecEnum.RWT, VecEnum.RWTa).map { enumVal =>

      // map to IDF vectors because those are what are used in VectorEmbeddingsService.documentSimilarity
      val vectorGetter: UserSimilarity.VectorGetterType =
        _.vectors.collect { case (k, v) if k == enumVal.toString => VecEnum.IDF -> v }

      new UserSimilarityBase(vectorGetter = vectorGetter)
    }

  import com.hamstoo.stream.StreamDSL._

  // filter out Nones (a.k.a. flatten the DataStreams)
  private val confirmatory :: antiConfirmatory :: Nil: Seq[DataStream[Double]] =
    Seq(confirmatoryOpt, antiConfirmatoryOpt).map(_.flatten)

  override val in: SourceType = (confirmatory - antiConfirmatory).out
}


/**
  * Endowment Bias facet is the difference between the similarity of a user's aggregate vectors (i.e.
  * UserStats.vectors) to a mark's user-content repr minus the similarity of a user's aggregate vectors to
  * the same mark's page-content repr.  In other words, a user is "endowed" with her own content (text she
  * highlights or notes/comments she writes herself; see RepresentationController.processUserContent) but
  * not "endowed" with the content she is marking (stuff she doesn't touch).
  *
  * TODO: we could compute the same for ratings-weighted (confirmatoryKws) endowment bias?
  */
@Singleton
class EndowmentBias @Inject()(implicit @Named(CallingUserId.name) mbUserId: CallingUserId.typ,
                              reprs: ReprsStream,
                              userStatDao: UserStatDao,
                              mat: Materializer) extends DataStream[Double] {

  private val vectorOp: UserSimilarity.VectorOpType = (mbPageRepr: Option[RSearchable],
                                                       mbUserContentRepr: Option[RSearchable],
                                                       userVecs: Map[Representation.VecEnum.Value, Vec]) => {

    val mbPageSimilarity :: mbUserContentSimilarity :: Nil = Seq(mbPageRepr, mbUserContentRepr).map { mbRepr =>
      val mbVec = mbRepr.flatMap(_.vectors.get(VecEnum.IDF.toString))
      mbVec.map(v => VectorEmbeddingsService.documentSimilarity(v, userVecs))
    }

    // difference between user-content repr similarity and page-content repr similarity
    if (mbPageSimilarity.isEmpty || mbUserContentSimilarity.isEmpty) None
    else Some(mbUserContentSimilarity.get - mbPageSimilarity.get)
  }

  private val base = new UserSimilarityBase(vectorOp = vectorOp)

  import com.hamstoo.stream.StreamDSL._
  override val in: SourceType = base.flatten.out
}