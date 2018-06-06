/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.hamstoo.stream.{DataStream, OptionalInjectId}
import com.hamstoo.stream.Data.ExtendedData
import com.hamstoo.utils.ExtendedTimeStamp

import math.{max, min}
import scala.reflect.classTag

/**
  * For weights above 0.5, this model will give more weight to semantic and user-content search scores.  For
  * weights below 0.5, it will give more weight to syntactic (e.g. exact search words) and marked-content
  * (i.e. website content).
  *
  * @param semWgt         Semantic weight.
  * @param usrWgt         User-content weight.
  * @param searchResults  Search results data stream.
  */
@Singleton
class AggregateSearchScore @Inject()(semWgt: AggregateSearchScore.SemanticWeight,
                                     usrWgt: AggregateSearchScore.UserContentWeight,
                                     searchResults: SearchResults)
                                    (implicit mat: Materializer)
    extends DataStream[Double] {

  import AggregateSearchScore._

  logger.info(f"Semantic weight: ${semWgt.value}%.2f, user-content weight: ${usrWgt.value}%.2f, ")

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._

    val relevance: DataStream[SearchRelevance] = searchResults("_3", classTag[Option[SearchRelevance]]).flatten

    // weights along 2 spectrums (range between 0 and 2)
    val w_sem = min(max(semWgt.value, 0), 1) * 2
    val w_usr = min(max(usrWgt.value, 0), 1) * 2

    // 4 weights, one for the end of each spectrum (range between 0 and 1)
    val w_uraw = (2 - w_sem) *      w_usr
    val w_usem =      w_sem  *      w_usr
    val w_rraw = (2 - w_sem) * (2 - w_usr)
    val w_rsem =      w_sem  * (2 - w_usr)

    val value = w_uraw * relevance("uraw") +
                w_usem * relevance("usem") +
                w_rraw * relevance("rraw") +
                w_rsem * relevance("rsem")

    value * COEF

    // uncomment this line to see the effect of not terminating streams as the last test in FacetsTests tests for
    // (i.e. the stream graph that's constructed does not get terminated)
    //searchResults.map(_ => 3.0)

  }.out.map { d => logger.debug(s"${d.sourceTimeMax.tfmt}"); d }
}

object AggregateSearchScore {

  val COEF = 1.0

  // 0.5 weights semantic/syntactic (https://en.wikipedia.org/wiki/Semantic_similarity) content evenly, and
  // user/marked content evenly
  val DEFAULT = 0.5

  case class SemanticWeight() extends OptionalInjectId[Double]("sem", DEFAULT)
  case class UserContentWeight() extends OptionalInjectId[Double]("usr", DEFAULT)
}


