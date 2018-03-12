package com.hamstoo.stream

import akka.NotUsed
import akka.stream.{Attributes, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, Source}
import com.hamstoo.stream.Tick.{ExtendedTick, Tick}
import com.hamstoo.utils.{DurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.Future

/**
  * A dynamic BroadcastHub wrapper around an Akka Stream.
  */
abstract class DataStream[T](implicit materializer: Materializer) {

  val logger = Logger(classOf[DataStream[T]])

  /** Pure virtual Akka Source to be defined by implementation. */
  protected def hubSource: Source[Data[T], NotUsed]

  /**
    * This `lazy val` materializes `hubSource` into a dynamic BroadcastHub, which can be wired into as many
    * Flows or Sinks as desired at runtime.
    * See also: https://doc.akka.io/docs/akka/2.5/stream/stream-dynamic.html
    */
  final lazy val source: Source[Data[T], NotUsed] = {
    assert(hubSource != null) // this assertion will fail if `source` is not `lazy`
    logger.debug(s"Materializing BroadcastHub...")
    val x = hubSource//.runWith(BroadcastHub.sink(bufferSize = 256))
    logger.debug(s"Done materializing BroadcastHub")
    x
  }
}

/**
  * A DataSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.  It throttles its stream emissions to 1/`period` frequency.
  */
abstract class DataSource[T](interval: DurationMils)
                            (implicit clock: Clock, materializer: Materializer) extends DataStream[T] {

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  def preload(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[T]]]

  /** Determines when to call `load` based on DataSource's periodicity. */
  case class PreloadTimer(var lastRangeEnd: TimeStamp = -1) {

    /** Return time ranges over which to perform next calls to `preload` up to, but not including, next clock tick. */
    def rangesFor(tick: Tick, clockInterval: DurationMils): List[(TimeStamp, Option[(TimeStamp, TimeStamp)]] = {

      // if this is the first load, then snap tick.time to an interval boundary, o/w use the end of the last range
      val rangeBegin = if (lastRangeEnd == -1) tick.time / interval * interval else lastRangeEnd

      val nextClockTick = tick.time + clockInterval

      // if `interval` is bigger than `clockInterval` we might have already loaded past current `tick.time`
      val nIntervals = (nextClockTick - rangeBegin) / interval

      // return ranges up to, but not including, nextClockTick
      val ranges = (0 until nIntervals.toInt).map { i =>
        val begin_i = rangeBegin + i * interval
        (tick.time, Some((begin_i, begin_i + interval)))
      }

      // it's important that the ranges are ordered correctly
      assert(ranges.isEmpty || ranges.last._2.get._2 < nextClockTick && nextClockTick <= ranges.last._2.get._2 + interval)

      // send back a None range so that we
      if (ranges.isEmpty) List((tick.time, None)) else ranges.toList
    }
  }

  /** Calls abstract `load` for each consecutive pair of clock ticks. */
  override protected val hubSource: Source[Data[T], NotUsed] = {

    logger.warn(s"Wiring ${this.getClass.getSimpleName}...")

    val preloadSource = clock.source
      .addAttributes(Attributes.inputBuffer(initial = 1, max = 1))
      //.alsoTo(ClockThrottle())
      .map { elem => logger.warn(s"ggggggggggggggggggggggggggggggggggggggg got clock: ${elem.oval.get.value.dt}"); elem }
      .statefulMapConcat { () => // each materialization calls this function (so there's one PreloadTimer each mat too)
        val timer = PreloadTimer()
        tick => timer.rangesFor(tick, clock.interval) // this function is called for each tick
      }
      .map { e => logger.warn("statefulMapConcat clock"); e }
      .mapAsync(8) { rg => (preload _).tupled } // mapAsync preserves ordering, unlike mapAsyncUnordered, which is essential
      .map { e => logger.warn("mapAsync clock"); e }
      .mapConcat(identity) // https://www.beyondthelines.net/computing/akka-streams-patterns/
      .addAttributes(Attributes.inputBuffer(initial = 1, max = 1))
      .buffer(1, OverflowStrategy.backpressure)

    //preloadSource.throttle
    //ZipWith

    logger.warn(s"Done wiring ${this.getClass.getSimpleName}")

    //preloadSource -> ClockThrottle()
    preloadSource.via(ClockThrottle()(clock))
    //preloadSource
  }
}


/*

1. each variable registers itself in the cache/Injector as a materialized broadcast hub
2. the variables are singletons so when they are requested the Injector provides the same instance to all
3. each time a variable is registered it is with a clock start offset differential (requires `extends Injector`)
  3b. the max offset differential is tracked for each variable
  3c. this is when the variable needs to start paying attention to clock ticks and streaming its data
4. the clock is started with the max of all differentials
5. all variables listen to the clock (itself a BroadcastHub)
6. variables have their own frequencies, independent of the clock
  6b. if they load data faster than the clock then more than one slice gets emitted each clock tick
  6c. if they load data slower than the clock then no new data could be emitted at some ticks


I. can the implementation of a function be mangled into a checksum? for versioning/dependency purposes
  - see: https://hamstoo.com/my-marks/KnwYcpMv4OQxoNAI





 */
