/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import akka.stream.Materializer
import com.google.inject.{Inject, Singleton}
import com.google.inject.name.Named
import com.hamstoo.stream._
import com.hamstoo.utils.{DurationMils, TimeStamp}
import org.joda.time.DateTime

import scala.concurrent.duration._

/**
  * The values of this facet are higher for more recent marks and lower for older marks, with a maximum value of 1.
  *
  * @param halfLife0  Decay rate.
  * @param now0       Current date-time.
  * @param marks      MarksStream
  */
@Singleton
class Recency @Inject() (halfLife0: Recency.HalfLifeOptional,
                         now0: Recency.CurrentTimeOptional,
                         marks: MarksStream)
                        (implicit m: Materializer)
    extends DataStream[Double] {

  val halfLife: DurationMils = halfLife0.value
  val now: TimeStamp = now0.value

  import com.hamstoo.stream.StreamOps._
  //import spire.implicits._

  override val hubSource: SourceType = {

    // this doesn't compile when spire.algebra.NRoot is used in place of Powable in StreamOps, the
    // `import spire.implicits._` seems to convert `now` into some implicit Spire type and then the compiler thinks
    // that there also needs to be a Ring[DataStream] to perform the subtraction, here's the error message:
    //   "could not find implicit value for parameter ev: spire.algebra.Ring[com.hamstoo.stream.DataStream[Double]]"

    val timeSince: DataStream[TimeStamp] = now - StreamOps(marks).timeFrom
    val nHalfLifes: DataStream[Double] = timeSince / halfLife
    0.5 pow nHalfLifes

  }.source
}

object Recency {

  /** Optional half-life parameter for compuation of Recency facet.  Memories fade over time. */
  case class HalfLifeOptional() extends OptionalInjectId[DurationMils] {
    final val name = "recency.halfLife"
    @Inject(optional = true) @Named(name) val value: DurationMils = (365 * 2).days.toMillis
  }

  /** Optional current time parameter for compuation of Recency facet. */
  case class CurrentTimeOptional() extends OptionalInjectId[TimeStamp] {
    final val name = "current.time"
    @Inject(optional = true) @Named(name) val value: TimeStamp = DateTime.now.getMillis
  }
}
