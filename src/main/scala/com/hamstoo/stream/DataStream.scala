/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Sink, Source, SourceQueue}
import com.hamstoo.stream.Tick.{ExtendedTick, Tick}
import com.hamstoo.stream.Join.{DEFAULT_EXPIRE_AFTER, JoinWithable, Pairwised}
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.{immutable, mutable}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/**
  * A dynamic BroadcastHub wrapper around an Akka Stream.
  *
  * @param bufferSize "Buffer size used by the producer. Gives an upper bound on how "far" from each other two
  *                   concurrent consumers can be in terms of element. If this buffer is full, the producer
  *                   is backpressured. Must be a power of 2 and less than 4096."
  *                     [https://doc.akka.io/japi/akka/current/akka/stream/scaladsl/BroadcastHub.html]
  */
abstract class DataStream[+T](bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                             (implicit mat: Materializer) {

  val logger = Logger(getClass)

  // nearly every concrete implementation of this class will require an implicit ExecutionContext
  implicit val ec: ExecutionContext = mat.executionContext

  // don't even try mentioning T anywhere in this type definition, more at the link
  //   https://stackoverflow.com/questions/33458782/scala-type-members-variance
  type SourceType[+TT] = Source[Datum[TT], NotUsed]

  /** Abstract Akka Source (input port) to be defined by implementation. */
  protected def in: SourceType[T]

  /**
    * This `lazy val` materializes the input port into a dynamic BroadcastHub output port, which can be wired into
    * as many Flows or Sinks as desired at runtime.
    * See also: https://doc.akka.io/docs/akka/2.5/stream/stream-dynamic.html
    */
  final lazy val out: SourceType[T] = {
    assert(in != null) // this assertion will fail if `source` is not `lazy`
    logger.debug(s"Materializing ${getClass.getSimpleName} BroadcastHub")

    // "This Source [hub] can be materialized an arbitrary number of times, where each of the new materializations
    // will receive their elements from the original [in]."
    val hub = in.runWith(BroadcastHub.sink(bufferSize = bufferSize)) // upper bound on how far two consumers can be [PERFORMANCE]

    hub.named(getClass.getSimpleName)
      //.async // overkill? [PERFORMANCE]
  }

  /** Shortcut to the source.  Think of a DataStream as being a lazily-evaluated pointer to a Source[Data[T]]. */
  def apply(): SourceType[T] = this.out

  /**
    * Unordered data streams may want/need to override this so that `Join`s with them behave correctly.  It is here
    * in the base class so that it can be passed down through the "dependency tree"/"stream graph" via StreamDSL, but
    * perhaps there's a better way to pass along this value (with implicits?) that I haven't thought of.
    */
  val joinExpiration: DurationMils = DEFAULT_EXPIRE_AFTER
}

object DataStream {

  // we use a default of 1 because there appears to be a bug in BroadcastHub where if the buffer consumes
  // the producer before any flows/sinks are attached to the materialized hub the producer will stop
  // and the dependent flows/sinks won't ever have a chance to backpressure
  //   [https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely]
  // update: changing this from 1 to 16 may have a big (positive) effect
  //   [http://blog.colinbreck.com/maximizing-throughput-for-akka-streams]
  val DEFAULT_BUFFER_SIZE = 16 // [PERFORMANCE]
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
abstract class PreloadSource[+T](val loadInterval: DurationMils, bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                                (implicit clock: Clock, mat: Materializer)
    extends DataStream[T](bufferSize) {

  logger.debug(s"Constructing ${getClass.getSimpleName} (loadInterval=${loadInterval.toDays})")

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  type PreloadCollectionType[+TT] = Traversable[Datum[TT]]
  type PreloadType[+TT] = Future[PreloadCollectionType[TT]]
  protected def preload(begin: TimeStamp, end: TimeStamp): PreloadType[T]

  /** PreloadSources' preloads are _subjects_ that are _observable_ by other PreloadSources' preloads. */
  private val preloadObservers = mutable.Set.empty[PreloadObserver[_, _]] // TODO: see "existential" below
  private[stream] def registerPreloadObserver(observer: PreloadObserver[T, _]): Unit = preloadObservers += observer

  /** Similar to a TimeWindow (i.e. simple range) but with a mutable buffer reference to access upon CloseGroup. */
  class KnownData(b: TimeStamp, e: TimeStamp,
                  val buffer: PreloadType[T] = Future.failed(new NullPointerException))
      extends TimeWindow(b, e) {

    /** WARNING: note the Await inside this function; i.e. only use it for debugging. */
    override def toString: String = {
      val sup = super.toString
      if (logger.isDebugEnabled) sup.dropRight(1) + s", n=${Await.result(buffer, 15 seconds).size})" else sup
    }
  }

  /**
    * Determines when to call `load` based on DataSource's periodicity.  And, yes, this is a DataStream itself so
    * that if there are other PreloadSources that want to depend on the preloading that this one performs, they
    * can do so by listening to its `out` port.
    */
  case class PreloadFactory() {

    // more mutable state (see "mutable" comment in GroupCommandFactory)
    private[this] var lastPreloadEnd: Option[TimeStamp] = None
    private var buffer = Seq.empty[PreloadType[_]] // TODO: this existential `_` should really be a T, but how?

    // if anyone wants to peek at the data that are being preloaded (so, for example, they can perform their own
    // dependent preload) then they can listen to this source as a PreloadObserver
    // see also: http://loicdescotte.github.io/posts/play-akka-streams-queue/
    private val overflowStrategy = akka.stream.OverflowStrategy.backpressure
    private val observers: SourceQueue[PreloadType[_]] = // TODO: see "existential" above
      Source.queue[PreloadType[_]](1, overflowStrategy).to(Sink.foreach { batch: PreloadType[_] =>
        preloadObservers.foreach { ob =>
          ob.asInstanceOf[PreloadObserver[T, _]].preloadUpdate(batch.asInstanceOf[PreloadType[T]])
        }
      }).run()

    /** This method generates GroupCommands containing PreloadGroups which have Future data attached. */
    def knownDataFor(tick: Tick): KnownData = {
      val ts = tick.time
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
        logger.debug(s"(\033[2m${PreloadSource.this.getClass.getSimpleName}\033[0m) knownDataFor: ${ts.tfmt}, preload begin: [${(end_i - loadInterval).tfmt}, ${end_i.tfmt})")

        val batch = preload(end_i - loadInterval, end_i)
        observers.offer(batch) // notify observers
        buffer = buffer :+ batch
      }

      val bufferT = buffer.asInstanceOf[Seq[PreloadType[T]]] // TODO: see "existential" above (remove asInstanceOf)

      // partition the buffer into data that is inside/outside the tick window (exclusive begin, inclusive end]
      val fpartitionedBuffer = Future.sequence(bufferT).map { iter =>

        // note exclusive-begin/inclusive-end here, perhaps this should be handled earlier by, e.g., changing the
        // semantics of the `preload` function
        val x = iter.flatten.partition(d => window.begin < d.knownTime && d.knownTime <= window.end)
        if (logger.isTraceEnabled)
          Seq((x._1, "inside"), (x._2, "outside")).foreach { case (seq, which) =>
            logger.trace(s"(\033[2m${PreloadSource.this.getClass.getSimpleName}\033[0m) fpartitionedBuffer($which): ${seq.map(_.knownTime).sorted.map(_.tfmt)}") }
        x
      }

      // distribute the buffer data to the OpenGroup
      val ftickBuffer = fpartitionedBuffer.map(_._1)
      val preloadWindow = new KnownData(window.begin, window.end, ftickBuffer)

      // if there are any remaining, undistributed (future) data, put them into `buffer` for the next go-around
      buffer = Seq(fpartitionedBuffer.map(_._2.filter(_.knownTime > window.end)))

      preloadWindow
    }
  }

  /** Groups preloaded data into clock tick intervals and throttles it to the pace of the ticks. */
  override protected val in: SourceType[T] = {

    clock.out

      // flow ticks through the PreloadFactory which preloads (probably) big chucks of future data but then
      // only allows (probably) smaller chunks of known data to pass at each tick
      .statefulMapConcat { () =>
        val factory = PreloadFactory() // this factory is constructed once per stream materialization
        tick => immutable.Iterable(factory.knownDataFor(tick)) // lambda function called once per tick
      }

      // should only need a single thread b/c data must arrive sequentially per the clock anyway,
      // the end time of the KnownData window will be that of the most recent tick (brought here by statefulMapConcat)
      // (changing this from mapAsync(1) to 2 or 4 or 8 doesn't seem to have any effect)
      .mapAsync(1) { w: KnownData =>
        if (logger.isDebugEnabled) logger.debug(s"(\033[2m${getClass.getSimpleName}\033[0m) $w")
        w.buffer.map { buf =>
          buf.toSeq.sorted(Ordering[ABV]).map(d => d.copy(knownTime = w.end))
        }
      }

      // convert to an immutable
      .mapConcat(immutable.Iterable(_: _*))

      // allocate each PreloadSource its own Actor (http://blog.colinbreck.com/maximizing-throughput-for-akka-streams/)
      // there won't be many of these and they'll all typically be doing IO,
      // just have to make sure the clock doesn't slow them down
      .async // [PERFORMANCE]
  }
}

/**
  * This class is a PreloadSource, but one that observes another with access to the outcomes of its calls to
  * `preload`.  It is useful in the case when large blocks of data are buffered by one stream and large dependent
  * blocks need to be buffered by another.
  * @tparam I The type of data being observed--or streamed (I)n.
  * @tparam O The type of data being streamed (O)ut.
  */
abstract class PreloadObserver[-I, +O](subject: PreloadSource[I], bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                                      (implicit clock: Clock, materializer: Materializer)
    extends PreloadSource[O](subject.loadInterval, bufferSize) {

  // don't forget to observe the subject, which is the whole reason why we're here
  subject.registerPreloadObserver(this)

  // cache of previous calls to `preloadUpdate` (should we enforce there being only 1 in the cache at a time?)
  private[this] val queue = mutable.Queue(Promise[PreloadCollectionType[O]]())

  /** Calls to this method are triggered by the `subject` when it performs one of its own calls to `preload`. */
  def preloadUpdate(subjectData: PreloadType[I]): Unit = {
    queue.last.completeWith(observerPreload(subjectData)) // 1. observerPreload called and quickly returns a Future
    queue.enqueue(Promise[PreloadCollectionType[O]]()) // 2. immediately, a new "empty" (ha!) Promise gets enqueued
  }

  /** Override the typical `preload` implementation with one that waits on the head of the cache queue. */
  override def preload(begin: TimeStamp, end: TimeStamp): PreloadType[O] =
    queue.head.future.map { x => queue.dequeue(); x } // 3. observerPreload's Future completes and immediately dequeued

  /** Abstract analogue of PreloadSource.preload for a PreloadObserver. */
  def observerPreload(subjectData: PreloadType[I]): PreloadType[O]
}

/**
  * A ThrottledSource is a DataStream that loads into its internal Stream's buffer--as opposed to a special
  * chunked "preload buffer"--at whatever rate Akka's backpressure mechanism will allow, but then it throttles
  * the doling out of its data such that it never gets ahead of a Clock.
  */
abstract class ThrottledSource[T](bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE)
                                 (implicit clock: Clock, materializer: Materializer)
    extends DataStream[T](bufferSize) {

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
  def throttlee: SourceType[T]

  /** Throttles data by joining it with the clock and then mapping back to itself. */
  override protected val in: SourceType[T] = {

    // the Join that this function uses should only have a single clock tick in its joinable1 buffer at a time, every
    // call to pushOneMaybe will either push or result in a pull from the throttlee, as soon as the throttlee's
    // watermark0 gets ahead of the clock's watermark1

    /** Move `d.knownTime`s up to the end of the clock window that they fall inside, just like PreloadSource. */
    def pairwise(d: Datum[T], t: Tick): Option[Join.Pairwised[T, TimeStamp]] =
      if (d.knownTime > t.time) None // throttlee known time must come before current clock time to be emitted
      else Some(Pairwised(Datum((d.value, 0L), d.id, d.sourceTime, t.time), consumed0 = true)) // throttlee consumed

    /** Simply ignore the 0L "value" that was paired up with each `sv.value` in `pairwise`. */
    def joiner(v: T, t: TimeStamp): T = v

    // throttle the `throttlee` with the clock (see comment on JoinWithable as to why the cast is necessary here)
    JoinWithable(throttlee).joinWith(clock.out)(joiner, pairwise).asInstanceOf[SourceType[T]]
  }
}