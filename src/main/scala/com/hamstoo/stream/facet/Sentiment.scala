/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Representation.VecFunctions
import com.hamstoo.stream.DataStream
import com.hamstoo.stream.dataset.{ReprQueryResult, RepredMarks, ReprsPair}

/**
  * Compute the average sentiment from a mark's page- and user-repr, assuming they both exist.
  * @param repredMarks  Data source of marks paired with their representations.
  */
@Singleton
class Sentiment @Inject()(repredMarks: RepredMarks)(implicit mat: Materializer) extends DataStream[Double] {

  import Sentiment._

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._
    repredMarks.map {
      case (_, ReprsPair(ReprQueryResult(mbPageRepr, _), ReprQueryResult(mbUsrRepr, _))) =>
        val sents = Seq(mbPageRepr, mbUsrRepr).flatMap(_.flatMap(_.sentiment))
        if (sents.isEmpty) DEFAULT_SENTIMENT else sents.mean
    }
  }.out
}

object Sentiment {
  // for some reason the way we compute sentiment values leads to lots of negatives, though perhaps not on
  // stage, in the future maybe we should correct for skew also
  // > db.representations.find({sentiment:{$exists:1}}, {sentiment:1, _id:0})
  // LOCAL:
  // { "sentiment" : -0.32608695652173914 }
  // { "sentiment" : -0.35873015873015873 }
  // { "sentiment" : -1 }
  // { "sentiment" : -0.3079710144927536 }
  // { "sentiment" : -0.8 }
  // { "sentiment" : -0.4444444444444444 }
  // { "sentiment" : -0.8333333333333334 }
  // { "sentiment" : 0 }
  // { "sentiment" : -0.3333333333333333 }
  // STAGE:
  // { "sentiment" : 0 }
  // { "sentiment" : 0.14285714285714285 }
  // { "sentiment" : 0 }
  // { "sentiment" : 0 }
  // { "sentiment" : 0 }
  // { "sentiment" : 0.75 }
  // { "sentiment" : 0 }
  val DEFAULT_SENTIMENT: Double = 0.0
}