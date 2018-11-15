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

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.{Success, Try}

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
  logger.info(s"\033[33mConstructing $this\033[0m (hashCode=$hashCode)")

  /**
    * Since a DataStream employs an Akka BroadcastHub under the covers, the clock ticks will begin progressing as soon
    * as the first consumer is attached.  Indeed it seems the ticks begin processing immediately, to be saved into
    * the BroadcastHub's buffer, even before any consumers are attached.  So we need to wait until the entire graph
    * is constructed and all the consumers are hooked up before ticks start incrementing.
    *
    * Using a Promise here has the extra benefit that `start` can only be called once.
    */
  private[this] val started = Promise[Unit]()
  def start(): Unit = {
    logger.info(s"\033[33mStarting $this\033[0m")
    started.success {}

    // TODO: maybe signal demand in here to ensure that Source.fromIterator.next gets triggered
    /*logger.info(s"Signaling demand from ${getClass.getSimpleName} with Sink.ignore")
    val _: Future[Done] = out
      .map { x => logger.debug(s"Clock: ${x.knownTime.tfmt}") }
      .runWith(Sink.ignore)*/
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
        if (!bool) logger.info(s"\033[33mClock complete\033[0m")
        bool
      }

      /** Iterator protocol. */
      override def next(): Tick = {

        // send a nullTick that consumers won't ever see (due to the filter in `out`) but which will (should!) trigger
        // BroadcastHub's GraphStageLogic.createLogic if it didn't run upon hub construction (issue #340)
        if (!nullTickSent) {
          logger.info(s"Sending clock's null tick")
          nullTickSent = true
          nullTick

        } else {

          // wait for `started` to be true before ticks start ticking
          if (!started.future.isCompleted) {
            logger.info(s"Infinitely awaiting clock to start...")

            //Await.result(started.future, Duration.Inf)

            // sometimes Await'ing here doesn't work ...
            // 2018-10-29 23:03:14,519 [info] c.h.s.Clock(153) - Constructing Clock(2017-01-01Z [1483.2288], 2018-10-29T23:03:14.518Z [1540.854194518], 100.0 days [8.64]) (hashCode=755499532)
            // 2018-10-29 23:03:14,544 [info] c.h.s.Clock(153) - Sending clock's null tick
            // 2018-10-29 23:03:14,545 [info] c.h.s.Clock(153) - Starting Clock(2017-01-01Z [1483.2288], 2018-10-29T23:03:14.518Z [1540.854194518], 100.0 days [8.64])
            // 2018-10-29 23:03:14,546 [info] c.h.s.Clock(153) - Infinitely awaiting clock to start
            // 2018-10-29 23:03:14,551 [debug] c.h.d.MarkDao(122) - Retrieving marks for user 99999999-9999-aaaa-aaaa-aaaaaaaaaaaa and tags Set() between 2017-02-05Z [1486.2528] and 2017-08-07Z [1502.064] (requireRepr=false)
            // ...
            // 2018-10-29 23:03:14,583 [debug] c.h.d.RepresentationDao(122) - Retrieved 0 representations given 0 IDs (0.007 seconds)
            // java.util.concurrent.TimeoutException: Timeout occurred after 60004 milliseconds

            // ... and sometimes it does ...
            // 2018-10-29 19:46:49,739 [info] c.h.s.Clock(153) - Constructing Clock(2017-01-01T05Z [1483.2468], 2018-10-29T23:46:49.738Z [1540.856809738], 100.0 days [8.64]) (hashCode=1415138947)
            // 2018-10-29 19:46:49,740 [info] c.h.s.Clock(153) - Sending clock's null tick
            // 2018-10-29 19:46:49,742 [info] c.h.s.Clock(153) - Infinitely awaiting clock to start
            // 2018-10-29 19:46:49,746 [info] c.h.s.Clock(153) - Starting Clock(2017-01-01T05Z [1483.2468], 2018-10-29T23:46:49.738Z [1540.856809738], 100.0 days [8.64])
            // 2018-10-29 19:46:49,749 [debug] c.h.d.MarkDao(122) - Retrieving marks for user 99999999-9999-aaaa-aaaa-aaaaaaaaaaaa and tags Set() between 2016-08-06Z [1470.4416] and 2017-02-05Z [1486.2528] (requireRepr=false)

            // ... which seems to have to do with "Infinitely" occurring after "Starting" race condition somehow ...
            // TODO: ... or perhaps the whole nullTick thing isn't properly addressing the BroadcastHub issue?

            @tailrec
            def wait10(): Unit = Try(Await.result(started.future, 10 seconds)) match {
              case Success(_) => logger.info("Done waiting for clock to start"); ()
              case _ => logger.info(s"Waiting another 10 seconds for clock to start..."); wait10()
            }

            wait10()
          }

          // would it ever make sense to have a clock Datum's knownTime be different from its sourceTime or val?
          val previousTime = currentTime
          currentTime = math.min(currentTime + interval, end) // ensure we don't go beyond `end`
          logger.debug(s"\033[33mTICK: ${currentTime.tfmt}\033[0m")
          Tick(currentTime, previousTime)
        }
      }
    }
  }.named("Clock")

  /**
    * We send a "null" timestamp, even before started.isCompleted, to trigger registration of BroadcastHub
    * consumers, if they happen to be too slow to register themselves on their own.
    * See also:
    *   https://github.com/akka/akka/issues/25608
    *   https://github.com/fcrimins/akka/tree/wip-hub-deadlock-akka-stream
    */
  private[this] var nullTickSent: Boolean = false
  private[this] val nullTick = Tick(0, 0)
  private[this] var firstNonNullTickLogged = false

  // TODO: when nullTick gets filtered out must upstream demand be re-demanded or is upstream demand still present?
  // TODO:   this could be the cause of the Await.result problem above
  override def out: SourceType = super.out.filter { tick =>
    if (tick == nullTick) {
      logger.info("Filtering out clock's null tick")
      false
    } else {
      if (!firstNonNullTickLogged) logger.info("Logging first non-null tick")
      firstNonNullTickLogged = true
      true
    }
  }
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
