/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.stream.DataStream
import com.hamstoo.stream.dataset.MarksStream

import scala.reflect.classTag

/**
  * Simply extract the rating from a mark's MarkData.
  * @param marks  Marks data source.
  */
@Singleton
class Rating @Inject()(marks: MarksStream)(implicit mat: Materializer) extends DataStream[Double] {

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._
    marks.rating.map(_.getOrElse(2.5)) // TODO: should this compute an average for the user rather than using 2.5 const?
  }.out
}

/**
  * log([mark.aux.totalVisible minutes] + e) + log(nOwnerVisits*2 + e) - 2
  *   both terms of which have a lower bound of 1.0 (when totalVisible and nOwnerVisits are 0).
  * This facet can be thought of as a sort of implicit rating--i.e. how much time the user spent at the site and/or
  *   number of times visited the mark.
  * @param marks  Marks data source.
  */
@Singleton
class ImplicitRating @Inject()(marks: MarksStream)(implicit mat: Materializer) extends DataStream[Double] {

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._
    marks("aux", classTag[Option[MarkAux]]).map { mbAux =>
      val durationMils = mbAux.flatMap(_.totalVisible).getOrElse(2L * 60 * 1000) // 2 minutes default
      val durationMins = durationMils.toDouble / 1000 / 60

      // add `e` so that the lower bound is 1 (but then subtract 1 after logging to shift lb back to 0)
      val logTimeSpent = math.log(durationMins + math.E) - 1.0

      // number of times the owner of the mark has visited its full-page view
      val nOwnerVisits = mbAux.flatMap(_.nOwnerVisits).fold(0.5)(_.toDouble)
      val logOwnerVisits = math.log(nOwnerVisits * 4 + math.E) - 1.0

      logTimeSpent + logOwnerVisits
    }
  }.out
}