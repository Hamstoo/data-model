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

  // TODO
  override val hubSource: SourceType = marks().map(_.mapValue(_.timeFrom.toDouble))

}

object Recency {

  /** Optional half-life parameter to Recency facet.  Memories fade over time. */
  case class HalfLifeOptional() extends OptionalInjectId[DurationMils] {
    final val name = "recency.halfLife"
    @Inject(optional = true) @Named(name) val value: DurationMils = (365 * 2).days.toMillis
  }

  /** Optional current time parameter to Recency facet. */
  case class CurrentTimeOptional() extends OptionalInjectId[Option[TimeStamp]] {
    final val name = "current.time"
    @Inject(optional = true) @Named(name) val value: Option[TimeStamp] = Some(DateTime.now.getMillis)
  }
}
