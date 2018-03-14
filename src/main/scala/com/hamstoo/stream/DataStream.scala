package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.hamstoo.stream.Tick.{ExtendedTick, Tick}
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * A dynamic BroadcastHub (TODO) wrapper around an Akka Stream.
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
    logger.debug(s"Materializing ${getClass.getSimpleName} BroadcastHub...")
    val x = hubSource//.runWith(BroadcastHub.sink(bufferSize = 256)) // TODO
    logger.debug(s"Done materializing ${getClass.getSimpleName} BroadcastHub")
    x
  }
}

/**
  * A DataSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.  It throttles its stream emissions to 1/`period` frequency.
  */
abstract class DataSource[T](loadInterval: DurationMils)
                            (implicit clock: Clock, materializer: Materializer, ec: ExecutionContext)
    extends DataStream[T] {

  override val logger = Logger(classOf[DataSource[T]])
  logger.info(s"Constructing ${getClass.getSimpleName} (loadInterval=${loadInterval.toDays})")

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  type PreloadType = Future[Traversable[Datum[T]]]
  def preload(begin: TimeStamp, end: TimeStamp): PreloadType

  /** Similar to a TimeWindow (i.e. simple range) but with a mutable buffer reference to access upon CloseGroup. */
  class PreloadWindow(b: TimeStamp, e: TimeStamp, val buffer: PreloadType = Future.failed(new NullPointerException))
    extends TimeWindow(b, e)

  /** Determines when to call `load` based on DataSource's periodicity. */
  case class PreloadFactory() {

    // more mutable state (see "mutable" comment in GroupCommandFactory)
    private var lastPreloadEnd: Option[TimeStamp] = None
    private var buffer = Seq.empty[PreloadType]

    /** This method generates GroupCommands containing PreloadGroups which have Future data attached. */
    def knownDataFor[TS](tick: Data[TS]): PreloadWindow = {
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

      // map elements of the buffer to their respective clock tick time windows
      val fpartitionedBuffer = Future.sequence(buffer).map { iter =>

        // note exclusive-begin/inclusive-end here, perhaps this should be handled earlier by, e.g., changing the
        // semantics of the `preload` function
        val x = iter.flatten.partition(d => window.begin < d.knownTime && d.knownTime <= window.end)
        if (logger.isDebugEnabled)
          Seq((x._1, "inside"), (x._2, "outside")).foreach { case (seq, which) =>
            logger.debug(s"fpartitionedBuffer($which): ${seq.map(_.knownTime).sorted.map(_.tfmt)}") }
        x
      }

      // distribute the buffer data to the OpenGroup
      val ftickBuffer: PreloadType = fpartitionedBuffer.map(_._1)
      val preloadWindow = new PreloadWindow(window.begin, window.end, ftickBuffer)

      // if there are any remaining, undistributed (future) data, put them into `buffer` for the next go-around
      buffer = Seq(fpartitionedBuffer.map(_._2.filter(_.knownTime > window.end)))

      preloadWindow
    }
  }

  /** Groups preloaded data into clock tick intervals and throttles it to the pace of the ticks. */
  override protected val hubSource: Source[Data[T], NotUsed] = {

    val preloadSource: Source[Data[T], NotUsed] = clock.source

      // flow ticks through the PreloadFactory which preloads (probably) big chucks of future data but then
      // only allows (probably) smaller chunks of known data to pass at each tick
      .statefulMapConcat { () =>
        val factory = PreloadFactory()
        tick => immutable.Iterable(factory.knownDataFor(tick))
      }

      // should only need a single thread b/c data must arrive sequentially per the clock anyway
      .mapAsync(1) { w: PreloadWindow =>
        logger.debug(s"${getClass.getSimpleName}: $w")
        w.buffer.map { buf =>
          Data.groupByKnownTime(buf).toSeq.sortBy(_.knownTime).map(d => d.copy(knownTime = w.end))
        }
      }
      .mapConcat(immutable.Iterable(_: _*))

    logger.info(s"Done wiring ${getClass.getSimpleName}")

    preloadSource
  }
}