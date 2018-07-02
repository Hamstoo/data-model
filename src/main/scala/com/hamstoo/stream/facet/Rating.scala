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
    marks.rating.map(_.getOrElse(2.5)) // TODO: this should compute an average for the user perhaps rather than 2.5
  }.out
}

object Rating { val DEFAULT_ARG = 1.0 }

/**
  * log([mark.aux.totalVisible minutes] + e), which has a lower bound of 1.0 (when totalVisible is 0).
  * This facet can be thought of as a sort of implicit rating--i.e. how much time the user spent at the site.
  * @param marks  Marks data source.
  */
@Singleton
class LogTimeSpent @Inject()(marks: MarksStream)(implicit mat: Materializer) extends DataStream[Double] {

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._
    marks("aux", classTag[Option[MarkAux]]).map { mbAux =>
      val durationMils = mbAux.flatMap(_.totalVisible).getOrElse(0L)
      val durationMins = durationMils.toDouble / 1000 / 60

      // add `e` so that the lower bound is 1.0
      math.log(durationMins + math.E)
    }
  }.out
}

object LogTimeSpent { val DEFAULT_ARG = 1.0 }


