/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.hamstoo.stream.{DataStream, OptionalInjectId}
import com.hamstoo.stream.dataset.MarksStream
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, TimeStamp}
import org.joda.time.DateTime

import math.{abs, log, pow}
import scala.concurrent.duration._

/**
  * For args above 0.5, the values of this model are higher for more recent marks and lower for older marks, with
  * a maximum value of 1.  For args below 0.5 the opposite is true.  0.5 is neutral.
  *
  * @param facetArg  User-provided model input argument, which gets translated into a half-life.
  * @param now       Current date-time.
  * @param marks     Marks data source.
  */
@Singleton
class Recency @Inject() (facetArg: Recency.Arg,
                         now: Recency.CurrentTimeOptional,
                         marks: MarksStream)
                        (implicit m: Materializer)
    extends DataStream[Double] {

  import Recency._

  // below 0.01 and above 0.99 the half-life gets very close to 0, near 0.5 it tends towards positive/negative infinity
  val cleanedArg: Option[Double] = facetArg.value match {
    case x if x < 0.01  => Some(0.01)
    case x if x < 0.481 => Some(x)
    case x if x < 0.519 => None // undefined: model will return constant 0.0
    case x if x < 0.99  => Some(x)
    case _              => Some(0.99)
  }

  // see data-model/docs/RecencyTest.xlsx for calculations of these values
  val EXPONENT = 6.74
  val DIVISOR = 0.1025737151

  val mbHalfLife: Option[DurationMils] = cleanedArg.map { a => {
    (if (a < 0.5) -1 else 1) * pow(-log(abs(a - 0.5)), EXPONENT) / DIVISOR
  }.days.toMillis }

  logger.info(s"Using a half-life of ${mbHalfLife.getOrElse(Long.MaxValue).dfmt} given arg value ${facetArg.value}")

  override val hubSource: SourceType = {
    import com.hamstoo.stream.StreamDSL._

    // this doesn't compile when spire.algebra.NRoot is used in place of Powable in StreamOps, the
    // `import spire.implicits._` seems to convert `now` into some implicit Spire type and then the compiler thinks
    // that there also needs to be a Ring[DataStream] to perform the subtraction, here's the error message:
    //   "could not find implicit value for parameter ev: spire.algebra.Ring[com.hamstoo.stream.DataStream[Double]]"
    //import spire.implicits._

    mbHalfLife.fold(marks.map(_ => 0.0)) { halfLife =>

      val timeSince = now.value - marks.timeFrom
      val nHalfLifes = timeSince / halfLife // note that half-life can be negative if arg < 0.491
      (0.5 pow nHalfLifes) * COEF

    }
  }.source.asInstanceOf[SourceType]
}

object Recency {

  val COEF = 4.0

  // 0.65 is equivalent to a 2-year (63072000017 ms) half-life
  val DEFAULT = 0.65

  /** Optional half-life (input) argument for computation of Recency model.  Memories fade over time. */
  case class Arg() extends OptionalInjectId[Double]("recency", DEFAULT)

  /** Optional current time parameter for compuation of Recency model. */
  case class CurrentTimeOptional() extends OptionalInjectId[TimeStamp]("current.time", DateTime.now.getMillis)
}
