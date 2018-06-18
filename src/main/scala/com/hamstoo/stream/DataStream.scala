/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Sink, Source, SourceQueue}
import com.hamstoo.stream.ElemStream._
import com.hamstoo.stream.Data.{Data, ExtendedData}
import com.hamstoo.stream.Join.{JoinWithable, Pairwised}
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.annotation.unchecked.uncheckedVariance
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
  * @param asyncConsumerBoundary  Setting this to `true` might create a separate actor for each attached consumer,
  *                               which is the point, so that the hub can round-robin to all of its consumers quickly
  *                               (i.e. w/ Futures) rather than having them all wait for each other to complete.
  *                               A better way to achieve this behavior, however, may be to make the consumers
  *                               use `out.async`.  More here:
  *                               http://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/
  * @param joinExpiration  Unordered data streams may want/need to override this so that `Join`s with them behave
  *                        correctly.  It is here in the base class so that it can be passed down through the
  *                        "dependency tree"/"stream graph" via StreamDSL, but perhaps there's a better way to pass
  *                        along this value (with implicits?) that I haven't thought of.
  */
abstract class ElemStream[+E](bufferSize: Int = ElemStream.DEFAULT_BUFFER_SIZE,
                              mbName: Option[String] = None)
                             (implicit mat: Materializer) {

  val logger = Logger(getClass)
  val name: String = mbName.getOrElse(getClass.getSimpleName)

  // nearly every concrete implementation of this class will require an implicit ExecutionContext
  implicit val ec: ExecutionContext = mat.executionContext

  // don't even try mentioning T anywhere in this type definition, more at the link
  //   https://stackoverflow.com/questions/33458782/scala-type-members-variance
  // update: @uncheckedVariance to the rescue!
  type SourceType = Source[E, NotUsed] @uncheckedVariance

  /** Abstract Akka Source (input port) to be defined by implementation.  Must be a `val`, not a `def`. */
  protected val in: SourceType

  /**
    * This `lazy val` materializes the input port into a dynamic BroadcastHub output port, which can be wired into
    * as many Flows or Sinks as desired at runtime.
    * See also: https://doc.akka.io/docs/akka/2.5/stream/stream-dynamic.html
    */
  final lazy val out: SourceType = {
    assert(in != null) // this assertion will fail if `source` is not `lazy`

    // the BroadcastHub does not appear to create an asynchronous stream boundary so everything before it
    // and everything after it are all running in the same Actor
    logger.debug(s"Materializing $name BroadcastHub")

    // "This Source [hub] can be materialized an arbitrary number of times, where each of the new materializations
    // will receive their elements from the original [in]."
    val hub = in.runWith(BroadcastHub.sink(bufferSize = bufferSize)) // upper bound on how far two consumers can be apart

    // re: async: this will/may create a separate actor for each attached consumer
    // re: buffer: "behavior can be tweaked" [https://doc.akka.io/docs/akka/current/stream/stream-dynamic.html]
    //   don't push across the async boundary until buffer is full (to make serialization more efficient)
    //(if (asyncConsumerBoundary) hub.async.buffer(bufferSize, OverflowStrategy.backpressure) else hub)
    hub.named(name) // `named` should be last, no matter what (b/c it's what the outside world sees)
  }

  /** Shortcut to the source.  Think of a ElemStream as being a lazily-evaluated pointer to a Source[Data[T]]. */
  def apply(): SourceType = this.out
}

object ElemStream {

  // changing this from 1 to 16 may have a (positive) effect
  //   [http://blog.colinbreck.com/maximizing-throughput-for-akka-streams]
  val DEFAULT_BUFFER_SIZE = 8
  val DEFAULT_BATCH_STREAM_BUFFER_SIZE = 1
}

/**
  * One batch, all with same timeKnown (hopefully) at each tick.
  */
abstract class DataStream[+T](bufferSize: Int = DEFAULT_BATCH_STREAM_BUFFER_SIZE,
                              mbName: Option[String] = None)
                             (implicit mat: Materializer)
    extends ElemStream[Data[T]](bufferSize, mbName) {}

/**
  * A PreloadSource is merely a ElemStream that can listen to a Clock so that it knows when to load
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
abstract class PreloadSource[+T](val loadInterval: DurationMils,
                                 bufferSize: Int = DEFAULT_BATCH_STREAM_BUFFER_SIZE)
                                (implicit clock: Clock, mat: Materializer)
    extends DataStream[T](bufferSize) {

  logger.debug(s"Constructing ${getClass.getSimpleName} (loadInterval=${loadInterval.toDays})")

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  type PreloadType[+TT] = Future[Data[TT]]
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
    private val observers: SourceQueue[(PreloadType[_], TimeStamp)] = // TODO: see "existential" above
      Source.queue[(PreloadType[_], TimeStamp)](1, overflowStrategy).to(Sink.foreach { case (batch, end_i) =>
        preloadObservers.foreach { ob =>
          ob.asInstanceOf[PreloadObserver[T, _]].preloadUpdate(batch.asInstanceOf[PreloadType[T]],
                                                               end_i - loadInterval, end_i)
        }
      }).run()

    /** This method generates GroupCommands containing PreloadGroups which have Future data attached. */
    def knownDataFor(tick: Tick): KnownData = {
      val ts = tick.time
      val window = TimeWindow(tick.previousTime, ts)

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

      // don't start querying the database for the next batch until at least the previous batch has completed for
      // 2 reasons: (1) we don't want a lot of concurrent database contention and (2) PreloadObserver.preload
      // always waits on the head of its queue (though this won't have much effect in practice because most
      // consecutive preloads occur during subsequent calls to knownDataFor)
      var batch: PreloadType[_] = Future.successful(immutable.Seq.empty[Datum[T]])

      // update the preload buffer by preloading new data
      (firstPreloadEnd until lastPreloadEnd.get by loadInterval).foreach { end_i =>

        // `preload` returns a Future, but it--the Future--immediately gets pushed to observers (rather than waiting)
        // so we can be sure that they get pushed onto the observers' queues in order
        batch = batch.flatMap { _ =>

          // ~~these calls to `preload` will be executed in parallel, but~~ the buffer appending won't be
          logger.debug(s"(\033[2m${PreloadSource.this.getClass.getSimpleName}\033[0m) Calling preload[${(end_i - loadInterval).tfmt}, ${end_i.tfmt}) from knownDataFor(${ts.tfmt})")

          preload(end_i - loadInterval, end_i)
        }

        observers.offer((batch, end_i)) // notify observers
        buffer = buffer :+ batch
      }

      val bufferT = buffer.asInstanceOf[Seq[PreloadType[T]]] // TODO: see "existential" above (remove asInstanceOf)

      // partition the buffer into data that is inside/outside the tick window (exclusive begin, inclusive end]
      val fpartitionedBuffer = Future.sequence(bufferT).map { iter =>

        // note exclusive-begin/inclusive-end here, perhaps this should be handled earlier by, e.g., changing the
        // semantics of the `preload` function
        val flat = iter.flatten
        val x = flat.partition(d => window.begin < d.knownTime && d.knownTime <= window.end)

        logger.debug(s"Partitioned ${flat.size} elements into sets of ${x._1.size} and ${x._2.size}")
        if (logger.isTraceEnabled)
          Seq((x._1, "inside"), (x._2, "outside")).foreach { case (seq, which) =>
            logger.trace(s"(\033[2m${PreloadSource.this.getClass.getSimpleName}\033[0m) fpartitionedBuffer($which): ${seq.map(_.knownTime).sorted.map(_.tfmt)}") }
        x
      }

      // distribute the buffer data to the OpenGroup
      val fknownBuffer = fpartitionedBuffer.map(_._1.map(_.copy(knownTime = window.end)).to[immutable.Seq])
      val preloadWindow = new KnownData(window.begin, window.end, fknownBuffer)

      // if there are any remaining, undistributed (future) data, put them into `buffer` for the next go-around
      buffer = Seq(fpartitionedBuffer.map(_._2.filter(_.knownTime > window.end).to[immutable.Seq]))

      preloadWindow
    }
  }

  /** Groups preloaded data into clock tick intervals and throttles it to the pace of the ticks. */
  override protected val in: SourceType = {

    clock.out
      //.async.buffer(1, OverflowStrategy.backpressure) // causes entire clock to be pulled immediately
      .map { t => logger.debug(s"PreloadSource: $t"); t }

      // flow ticks through the PreloadFactory which preloads (probably) big chucks of future data but then
      // only allows (probably) smaller chunks of known data to pass at each tick
      .statefulMapConcat { () =>
        val factory = PreloadFactory() // this factory is constructed once per stream materialization
        t => immutable.Iterable(factory.knownDataFor(t.asInstanceOf[Tick])) // lambda function called once per tick
      }

      // the end time of the KnownData window will be that of the most recent tick (brought here by statefulMapConcat),
      // this should probably stay at 1 b/c there's no need to overload the database with concurrent calls to preload
      // especially when we want the first ones to finish fastest (so that the graph execution can progress) anyway
      .mapAsync(1) { w: KnownData =>
          if (logger.isDebugEnabled) w.buffer.map(buf =>
            logger.debug(s"(\033[2m${getClass.getSimpleName}\033[0m) Elements: n=${buf.size}, $w"))
          w.buffer
        }

      // allocate each PreloadSource its own Actor (http://blog.colinbreck.com/maximizing-throughput-for-akka-streams/)
      // as there won't be many of these and they'll all typically be doing IO
      //.async
      // this seems like it slows things down (considerably) by making the stream system wait until
      //   this many elements are available before batching and pushing them across the async boundary
      //.buffer(1024, OverflowStrategy.backpressure)
      // update: don't add more threads than are needed, the reason the old search runs so much faster is because
      // it uses far fewer (contentious) threads, which is also why Akka doesn't insert `.async`s by default
  }
}

/**
  * This class is a PreloadSource, but one that observes another with access to the outcomes of its calls to
  * `preload`.  It is useful in the case when large blocks of data are buffered by one stream and large dependent
  * blocks need to be buffered by another.
  * @tparam I The type of data being observed--or streamed (I)n.
  * @tparam O The type of data being streamed (O)ut.
  */
abstract class PreloadObserver[-I, +O](subject: PreloadSource[I],
                                       bufferSize: Int = DEFAULT_BATCH_STREAM_BUFFER_SIZE)
                                      (implicit clock: Clock, materializer: Materializer)
    extends PreloadSource[O](subject.loadInterval, bufferSize) {

  // don't forget to observe the subject, which is the whole reason why we're here
  subject.registerPreloadObserver(this)

  // must signal demand from primary `out` source, o/w there might not be any data produced by the `subject` to observe
  subject.out.runWith(Sink.ignore)

  // cache of previous calls to `preloadUpdate` (using TrieMap rather than faster ConcurrentHashMap b/c the former
  // has `getOrElseUpdate`)
  private[this] val cache = new scala.collection.concurrent.TrieMap[TimeStamp, Promise[Data[O]]]

  /** Calls to this method are triggered by the `subject` after it performs one of its own calls to `preload`. */
  def preloadUpdate(fSubjectData: PreloadType[I], begin: TimeStamp, end: TimeStamp): Unit = {
    logger.debug(s"PreloadObserver encache (${begin.tfmt} to ${end.tfmt})")
    val fut = observerPreload(fSubjectData, begin, end)
    cache.getOrElseUpdate(end, Promise[Data[O]]()).completeWith(fut)
  }

  /** Override the typical `preload` implementation with one that waits on the appropriate cache element. */
  override def preload(begin: TimeStamp, end: TimeStamp): PreloadType[O] = {
    logger.debug(s"Commence PreloadObserver wait (${begin.tfmt} to ${end.tfmt})")
    cache.getOrElseUpdate(end, Promise[Data[O]]()).future.map { x =>
      logger.debug(s"PreloadObserver decache, wait complete (${begin.tfmt} to ${end.tfmt})")
      cache.remove(end)
      x
    }
  }

  /** Abstract analogue of PreloadSource.preload for a PreloadObserver. */
  def observerPreload(fSubjectData: PreloadType[I], begin: TimeStamp, end: TimeStamp): PreloadType[O]
}

/**
  * A ThrottledSource is a DataStream that loads into its internal Stream's buffer--as opposed to a special
  * chunked "preload buffer"--at whatever rate Akka's backpressure mechanism will allow, but then it throttles
  * the doling out of its data such that it never gets ahead of a Clock.
  */
abstract class ThrottledSource[T](bufferSize: Int = DEFAULT_BUFFER_SIZE)
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
  def throttlee: SourceType

  /** Throttles data by joining it with the clock and then mapping back to itself. */
  override protected val in: SourceType = {

    // the Join that this function uses should only have a single clock tick in its joinable1 buffer at a time, every
    // call to pushOneMaybe will either push or result in a pull from the throttlee, as soon as the throttlee's
    // watermark0 gets ahead of the clock's watermark1

    /** Move `d.knownTime`s up to the end of the clock window that they fall inside, just like PreloadSource. */
    def pairwise(d: Data[T], t: immutable.Seq[Datum[TimeStamp]]): Option[Join.Pairwised[T, TimeStamp]] =
      if (d.knownTimeMax > t.head.asInstanceOf[Tick].time)
        None // throttlee known time must come before current clock time to be emitted
      else Some(Pairwised(
        d.map(e => Datum((e.value, 0L), e.id, e.sourceTime, t.head.asInstanceOf[Tick].time)),
        consumed0 = true // throttlee (not clock tick) consumed
      ))

    /** Simply ignore the 0L "value" that was paired up with each `sv.value` in `pairwise`. */
    def joiner(v: T, t: TimeStamp): T = v

    // throttle the `throttlee` with the clock (see comment on JoinWithable as to why the cast is necessary here)
    JoinWithable(throttlee).joinWith(clock().map(t => Data(t)))(joiner, pairwise).asInstanceOf[SourceType]
  }
}