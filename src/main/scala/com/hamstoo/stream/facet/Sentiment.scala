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

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._
    repredMarks.map {
      case (_, ReprsPair(ReprQueryResult(mbPageRepr, _), ReprQueryResult(mbUsrRepr, _))) =>
        val sents = Seq(mbPageRepr, mbUsrRepr).flatMap(_.flatMap(_.sentiment))
        if (sents.isEmpty) 0.0 else sents.mean
    }
  }.out
}