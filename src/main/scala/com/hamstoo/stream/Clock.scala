/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TIME_NOW, TimeStamp}
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

/**
  * A mocked clock, implemented as an Akka Source.
  */
@com.google.inject.Singleton
case class Clock(begin: TimeStamp, end: TimeStamp, private val interval: DurationMils)
                (implicit mat: Materializer)

    extends ElemStream[Datum[TimeStamp]](bufferSize = 1) {
      // we use 1 here because there appears to be a bug in BroadcastHub where if the buffer consumes the
      // producer before any flows/sinks are attached to the materialized hub the producer will stop and the
      // dependent flows/sinks won't ever have a chance to backpressure (which is a problem for the clock especially!)
      //   [https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely]

  /** Dependency injection constructor--there can be only one.  Convert from OptionalInjectIds to their values. */
  @Inject def this(beginOpt: Clock.BeginOptional, endOpt: Clock.EndOptional, intervalOpt: Clock.IntervalOptional)
                  (implicit mat: Materializer) =
    this(beginOpt.value, endOpt.value, intervalOpt.value) // redirect to primary constructor

  override def toString: String = s"${getClass.getSimpleName}(${begin.tfmt}, ${end.tfmt}, ${interval.dfmt})"
  logger.info(s"\033[33mConstructing $this\033[0m ($hashCode)")

  /**
    * Since a DataStream employs an Akka BroadcastHub under the covers, the clock ticks will begin progressing as soon
    * as the first consumer is attached.  Indeed it seems the ticks begin processing immediately, to be saved into
    * the BroadcastHub's buffer, even before any consumers are attached.  So we need to wait until the entire graph
    * is constructed and all the consumers are hooked up before ticks start incrementing.
    *
    * Using a Promise here has the extra benefit that `start` can only be called once.
    */
  val started: Promise[Unit] = Promise()
  def start(): Unit = {
    logger.info(s"\033[33mStarting $this\033[0m ($hashCode)")
    started.success {}
  }

  /** Source derived from an iterator, not a range, for one so that intervals may eventually be made irregular. */
  override protected val in: Source[Tick, NotUsed] = Source.fromIterator { () =>

    new Iterator[Tick] {
      var currentTime: TimeStamp = begin

      /** Iterator protocol. */
      override def hasNext: Boolean = {

        // inclusive end: shouldn't hurt anything and could help (conservative), but note that we could skip
        // over end, making this point moot, if `end - start` is not an exact number of `interval`s
        val bool = currentTime < end
        if (!bool) logger.info(s"\033[33mClock complete\033[0m ($hashCode)")
        bool
      }

      /** Iterator protocol. */
      override def next(): Tick = {

        // wait for `started` to be true before ticks start incrementing
        Await.result(started.future, Duration.Inf)

        // would it ever make sense to have a clock Datum's knownTime be different from its sourceTime or val?
        val previousTime = currentTime
        currentTime = math.min(currentTime + interval, end) // ensure we don't go beyond `end`
        logger.debug(s"\033[33mTICK: ${currentTime.tfmt}\033[0m ($hashCode)")
        Tick(currentTime, previousTime)
      }
    }
  }.named("Clock")
}

object Clock {

  val DEFAULT_BEGIN: TimeStamp = new DateTime(2017, 1,  1, 0, 0).getMillis
  val DEFAULT_INTERVAL: DurationMils = (100 days).toMillis

  case class BeginOptional() extends OptionalInjectId[TimeStamp]("clock.begin", DEFAULT_BEGIN)
  case class IntervalOptional() extends OptionalInjectId[DurationMils]("clock.interval", DEFAULT_INTERVAL)

  // using Option[TimeStamp/Long] here works to construct the binding, but it doesn't work when it comes time for
  // injection because Scala's Long gets changed into a Java primitive `long` and then into an Option[Object] in
  // resulting bytecode, so ClockEnd.typ ends up being an Option[Object] that can't be found at injection time
  // more here: https://github.com/codingwell/scala-guice/issues/56
  // Error message: "No implementation for scala.Option<java.lang.Object> annotated with @com.google.inject.name
  // .Named(value=clock.end) was bound."
  case class EndOptional() extends OptionalInjectId[/*Option[java.lang.Long]*/TimeStamp]("clock.end", TIME_NOW)
}
