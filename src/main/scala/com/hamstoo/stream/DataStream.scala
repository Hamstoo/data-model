/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Source}
import com.hamstoo.stream.Tick.{ExtendedTick, Tick}
import com.hamstoo.stream.Join.{JoinWithable, Pairwised}
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Base class of all DataStreams with an abstract type member rather than a generic type parameter.
  * This trait is needed so that FacetsModel.source can merge a bunch of DataStreams of different types.
  */
trait DataStreamBase {

  // https://stackoverflow.com/questions/1154571/scala-abstract-types-vs-generics
  type DataType // abstract type member
  type SourceType = Source[Data[DataType], NotUsed]
  val source: SourceType
}

/**
  * A dynamic BroadcastHub wrapper around an Akka Stream.
  *
  * @param bufferSize "Buffer size used by the producer. Gives an upper bound on how "far" from each other two
  *                   concurrent consumers can be in terms of element. If this buffer is full, the producer
  *                   is backpressured. Must be a power of two and less than 4096."
  *                     [https://doc.akka.io/japi/akka/current/akka/stream/scaladsl/BroadcastHub.html]
  */
abstract class DataStream[T](bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                            (implicit materializer: Materializer) extends DataStreamBase {

  val logger = Logger(classOf[DataStream[T]])

  // TODO: put this in companion object so one can say SearchResults.DataType without needing an instance of SearchResults
  override type DataType = T

  /** Pure virtual Akka Source to be defined by implementation. */
  protected def hubSource: SourceType

  /**
    * This `lazy val` materializes `hubSource` into a dynamic BroadcastHub, which can be wired into as many
    * Flows or Sinks as desired at runtime.
    * See also: https://doc.akka.io/docs/akka/2.5/stream/stream-dynamic.html
    */
  override final lazy val source: SourceType = {
    assert(hubSource != null) // this assertion will fail if `source` is not `lazy`
    logger.debug(s"Materializing ${getClass.getSimpleName} BroadcastHub...")

    // "This Source [src] can be materialized an arbitrary number of times, where each of the new materializations
    // will receive their elements from the original [hubSource]."
    val src = hubSource.runWith(BroadcastHub.sink(bufferSize = bufferSize))

    logger.debug(s"Done materializing ${getClass.getSimpleName} BroadcastHub")
    src.named(getClass.getSimpleName)
  }

  /** Shortcut to the source.  Think of a DataStream as being a lazily-evaluated pointer to a Source[Data[T]]. */
  def apply(): SourceType = this.source
}

object DataStream {

  // we use a default of 1 because there appears to be a bug in BroadcastHub where if the buffer consumes
  // the producer before any flows/sinks are attached to the materialized hub the producer will stop
  // and the dependent flows/sinks won't ever have a chance to backpressure
  //   [https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely]
  val DEFAULT_BUFFER_SIZE = 1
}

/**
  * A PreloadSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.  It throttles its stream emissions to 1/`period` frequency.
  *
  * @param loadInterval The interval between consecutive "preloads" which will probably be much larger than
  *                     the clock's tick interval so that we can stay ahead of the clock.  The idea is that
  *                     this is likely an expensive (IO) operation that can be performed asynchronously in
  *                     advance of when the data is needed and then be throttled by the clock.
  * @param bufferSize "Buffer size used by the producer. Gives an upper bound on how "far" from each other two
  *                   concurrent consumers can be in terms of element. If this buffer is full, the producer
  *                   is backpressured. Must be a power of two and less than 4096."
  *                     [https://doc.akka.io/japi/akka/current/akka/stream/scaladsl/BroadcastHub.html]
  * @tparam T The type of data being streamed.
  */
abstract class PreloadSource[T](loadInterval: DurationMils, bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                               (implicit clock: Clock, materializer: Materializer, ec: ExecutionContext)
    extends DataStream[T](bufferSize) {

  override val logger = Logger(classOf[PreloadSource[T]])
  logger.info(s"Constructing ${getClass.getSimpleName} (loadInterval=${loadInterval.toDays})")

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  type PreloadType = Future[Traversable[Datum[T]]]
  def preload(begin: TimeStamp, end: TimeStamp): PreloadType

  /** Similar to a TimeWindow (i.e. simple range) but with a mutable buffer reference to access upon CloseGroup. */
  class KnownData(b: TimeStamp, e: TimeStamp, val buffer: PreloadType = Future.failed(new NullPointerException))
      extends TimeWindow(b, e) {

    /** WARNING: note the Await inside this function; i.e. only use it for debugging. */
    override def toString: String = {
      val sup = super.toString
      if (logger.isDebugEnabled) sup.dropRight(1) + s", n=${Await.result(buffer, 15 seconds).size})" else sup
    }
  }

  /** Determines when to call `load` based on DataSource's periodicity. */
  case class PreloadFactory() {

    // more mutable state (see "mutable" comment in GroupCommandFactory)
    private var lastPreloadEnd: Option[TimeStamp] = None
    private var buffer = Seq.empty[PreloadType]

    /** This method generates GroupCommands containing PreloadGroups which have Future data attached. */
    def knownDataFor[TS](tick: Data[TS]): KnownData = {
      val ts = tick.asInstanceOf[Tick].time
      val window = TimeWindow(ts - clock.interval, ts)

      // the first interval boundary strictly after ts
      def nextIntervalStart(ts: TimeStamp) = ts / loadInterval * loadInterval + loadInterval

      // if this is the first preload, then snap beginning of tick.time's clock interval to a loadInterval boundary,
      // o/w use the end of the last preload interval
      val firstPreloadEnd = lastPreloadEnd.getOrElse(nextIntervalStart(window.begin))

      // the first loadInterval boundary strictly after `window.end` (this interval must be preloaded now)
      lastPreloadEnd = Some(nextIntervalStart(window.end))
      assert(lastPreloadEnd.get > window.end)

      // add another loadInterval so that (1) we can use exclusive `until` in the loop below and (2) we can set
      // `firstPreloadEnd = lastPreloadEnd` in the next call to `knownDataFor`
      lastPreloadEnd = lastPreloadEnd.map(_ + loadInterval)

      // update the preload buffer by preloading new data
      (firstPreloadEnd until lastPreloadEnd.get by loadInterval).foreach { end_i =>

        // these calls to `preload` be executed in parallel, but the buffer appending won't be
        logger.debug(s"preload begin: [${(end_i - loadInterval).tfmt}, ${end_i.tfmt})")
        buffer = buffer :+ preload(end_i - loadInterval, end_i)
      }

      // partition the buffer into data that is inside/outside the tick window (exclusive begin, inclusive end]
      val fpartitionedBuffer = Future.sequence(buffer).map { iter =>

        // note exclusive-begin/inclusive-end here, perhaps this should be handled earlier by, e.g., changing the
        // semantics of the `preload` function
        val x = iter.flatten.partition(d => window.begin < d.knownTime && d.knownTime <= window.end)
        if (logger.isTraceEnabled)
          Seq((x._1, "inside"), (x._2, "outside")).foreach { case (seq, which) =>
            logger.trace(s"fpartitionedBuffer($which): ${seq.map(_.knownTime).sorted.map(_.tfmt)}") }
        x
      }

      // distribute the buffer data to the OpenGroup
      val ftickBuffer: PreloadType = fpartitionedBuffer.map(_._1)
      val preloadWindow = new KnownData(window.begin, window.end, ftickBuffer)

      // if there are any remaining, undistributed (future) data, put them into `buffer` for the next go-around
      buffer = Seq(fpartitionedBuffer.map(_._2.filter(_.knownTime > window.end)))

      preloadWindow
    }
  }

  /** Groups preloaded data into clock tick intervals and throttles it to the pace of the ticks. */
  override protected val hubSource: SourceType = {

    clock.source

      // flow ticks through the PreloadFactory which preloads (probably) big chucks of future data but then
      // only allows (probably) smaller chunks of known data to pass at each tick
      .statefulMapConcat { () =>
        val factory = PreloadFactory()
        tick => immutable.Iterable(factory.knownDataFor(tick))
      }

      // should only need a single thread b/c data must arrive sequentially per the clock anyway,
      // the end time of the KnownData window will be that of the most recent tick (brought here by statefulMapConcat)
      .mapAsync(1) { w: KnownData =>
        if (logger.isDebugEnabled) logger.debug(s"(\033[2m${getClass.getSimpleName}\033[0m) $w")
        w.buffer.map { buf =>
          Data.groupByKnownTime(buf).toSeq.sortBy(_.knownTime).map(d => d.copy(knownTime = w.end))
        }
      }
      .mapConcat(immutable.Iterable(_: _*))
  }
}

/**
  * A ThrottledSource is a DataStream that loads into its internal Stream's buffer--as opposed to a special
  * chunked "preload buffer"--at whatever rate Akka's backpressure mechanism will allow, but then it throttles
  * the doling out of its data such that it never gets ahead of a Clock.
  */
abstract class ThrottledSource[T](bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                                 (implicit clock: Clock, materializer: Materializer, ec: ExecutionContext)
    extends DataStream[T](bufferSize) {

  override val logger = Logger(classOf[ThrottledSource[T]])
  logger.info(s"Constructing ${getClass.getSimpleName}")

  /**
    * The Source to throttle.
    *
    * Overridden `throttlee`s may want to tweak their buffer sizes with one of the two following approaches.
    *   1. Akka internal, async buffer: set `bufferSize` field which the BroadcastHub imposes on its producer, i.e.
    *      do not use `addAttributes(Attributes.inputBuffer(initial = 8, max = 8))` which will have no effect.
    *   2. User-defined, domain logic buffer: throttlee.buffer(500, OverflowStrategy.backpressure)
    * [https://doc.akka.io/docs/akka/current/stream/stream-rate.html]
    */
  def throttlee: SourceType

  /** Throttles data by joining it with the clock and then mapping back to itself. */
  override protected val hubSource: SourceType = {

    // the Join that this function uses should only have a single clock tick in its joinable1 buffer at a time, every
    // call to pushOneMaybe will either push or result in a pull from the throttlee, as soon as the throttlee's
    // watermark0 gets ahead of the clock's watermark1

    /** Move `d.knownTime`s up to the end of the clock window that they fall inside, just like PreloadSource. */
    def pairwise(d: Data[T], t: Tick): Option[Join.Pairwised[T, TimeStamp]] =
      if (d.knownTime > t.time) None
      else Some(Pairwised(Data(t.time, d.values.mapValues(sv => SourceValue((sv.value, 0L), sv.sourceTime))),
                          consumed0 = true))

    /** Simply ignore the 0L "value" that was paired up with each `sv.value` in `pairwise`. */
    def joiner(v: T, t: TimeStamp): T = v

    // throttle the `throttlee` with the clock (see comment on JoinWithable as to why the cast is necessary here)
    JoinWithable(throttlee).joinWith(clock())(joiner, pairwise).asInstanceOf[SourceType]
  }
}