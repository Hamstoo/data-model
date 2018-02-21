package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.hamstoo.utils.TimeStamp

import scala.concurrent.duration.FiniteDuration

/**
  * A mocked clock implemented as an Akka Source.
  */
object Clock {

  type Clock = Source[TimeStamp, NotUsed]

  /** Construct a clock source, which doesn't start ticking until the graph gets run. */
  def apply(start: TimeStamp, stop: TimeStamp, interval: FiniteDuration): Clock =
    Source.fromIterator(() => clock(start, stop, interval))

  /** Clock iterator to iterate from `start` to `stop` by `interval`. */
  protected def clock(start: TimeStamp, stop: TimeStamp, interval: FiniteDuration): Iterator[TimeStamp] =
    new Iterator[TimeStamp] {
      var currentTime: TimeStamp = start

      override def hasNext : Boolean = currentTime < stop

      override def next(): TimeStamp = {
        val r = currentTime
        currentTime += interval.toMillis
        r
      }
    }
}