/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.hamstoo.stream.{DataStream, OptionalInjectId}
import com.hamstoo.stream.Data.ExtendedData
import com.hamstoo.stream.dataset.MarksStream
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TIME_NOW, TimeStamp}

import scala.concurrent.duration._

/**
  * For args above 0.5, the values of this model are higher for more recent marks and lower for older marks, with
  * a maximum value of 1.  For args below 0.5 the opposite is true.  0.5 is neutral.
  *
  * @param now       Current date-time.
  * @param marks     Marks data source.
  */
@Singleton
class Recency @Inject()(now: Recency.CurrentTimeOptional,
                        marks: MarksStream)
                       (implicit mat: Materializer)
    extends DataStream[Double] {

  // outdated: see data-model/docs/RecencyTest.xlsx for calculations of these values
  //val EXPONENT = 6.74
  //val DIVISOR = 0.1025737151

  // rather than using this complicated formula, just have a fixed 2-year half life with a parameterized COEF
  val HALF_LIFE: DurationMils = 365.days.toMillis
  logger.info(f"Using a half-life of ${HALF_LIFE.dfmt}")

  override val in: SourceType = {
    import com.hamstoo.stream.StreamDSL._

    // this doesn't compile when spire.algebra.NRoot is used in place of Powable in StreamOps, the
    // `import spire.implicits._` seems to convert `now` into some implicit Spire type and then the compiler thinks
    // that there also needs to be a Ring[DataStream] to perform the subtraction, here's the error message:
    //   "could not find implicit value for parameter ev: spire.algebra.Ring[com.hamstoo.stream.DataStream[Double]]"
    //import spire.implicits._

    val timeSince = now.value - marks.timeFrom
    val nHalfLifes = timeSince / HALF_LIFE
    0.5.pow(nHalfLifes)

  }.out.map { d => logger.debug(s"${d.sourceTimeMax.tfmt} (n=${d.size})"); d }
}

object Recency {

  /** Optional current time parameter for compuation of Recency model. */
  case class CurrentTimeOptional() extends OptionalInjectId[TimeStamp]("current.time", TIME_NOW)
}
