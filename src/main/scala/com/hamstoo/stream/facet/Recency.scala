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
  *
  * @param halfLife
  * @param now
  * @param marks
  * @param m
  */
@Singleton
class Recency @Inject() (halfLife: Recency.HalfLifeOptional,
                         now: Recency.CurrentTimeOptional,
                         marks: MarksStream)
                        (implicit m: Materializer)
    extends DataStream[Double] {

  import com.hamstoo.stream.StreamOps._

  override val hubSource: SourceType = {

    //implicit val doubleOpStream: OpStream[Double] = fractionalOpStream[Double]

    val x: Double = now.value.toDouble
    val y: DataStream[Double] = marks("timeFrom", scala.reflect.classTag[Double])

    //doubleOpStream.-(x, y)

    y - now.value // debugging/testing

    (now - marks("timeFrom")) / halfLife

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
