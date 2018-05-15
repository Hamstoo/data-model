/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Sink, Source, SourceQueue}
import com.hamstoo.stream.DataStream._
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
abstract class DataStream[+T](bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE,
                              asyncConsumerBoundary: Boolean = false)
                             (implicit mat: Materializer,
                              val joinExpiration: JoinExpiration = JoinExpiration()) {

  val logger = Logger(getClass)

  // nearly every concrete implementation of this class will require an implicit ExecutionContext
  implicit val ec: ExecutionContext = mat.executionContext

  // don't even try mentioning T anywhere in this type definition, more at the link
  //   https://stackoverflow.com/questions/33458782/scala-type-members-variance
  // TODO: try using @uncheckedVariance here instead (as it is used in Join.scala)
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

    // the BroadcastHub does not appear to create an asynchronous stream boundary so everything before it
    // and everything after it are all running in the same Actor
    logger.debug(s"Materializing ${getClass.getSimpleName} BroadcastHub (joinExpiration=${joinExpiration.x.dfmt})")

    // "This Source [hub] can be materialized an arbitrary number of times, where each of the new materializations
    // will receive their elements from the original [in]."
    val hub = in.runWith(BroadcastHub.sink(bufferSize = bufferSize)) // upper bound on how far two consumers can be apart

    // re: async: this will/may create a separate actor for each attached consumer
    // re: buffer: "behavior can be tweaked" [https://doc.akka.io/docs/akka/current/stream/stream-dynamic.html]
    //   don't push across the async boundary until buffer is full (to make serialization more efficient)
    (if (asyncConsumerBoundary) hub.async.buffer(bufferSize, OverflowStrategy.backpressure) else hub)
      .named(getClass.getSimpleName) // `named` should be last, no matter what
  }

  /** Shortcut to the source.  Think of a DataStream as being a lazily-evaluated pointer to a Source[Data[T]]. */
  def apply(): SourceType[T] = this.out
}

object DataStream {

  // changing this from 1 to 16 may have a (positive) effect
  //   [http://blog.colinbreck.com/maximizing-throughput-for-akka-streams]
  val DEFAULT_BUFFER_SIZE = 8







  // TODO: 1. try chaining batches first (pushed to stage)
  // while chaining batches is necessary, the problem is that consecutive mapAsync calls may also result out-of-order
  // completions of preload, so mapAsync must really be 1

  // TODO: 2. read the new article

  // TODO: 3. make SearchResults a PreloadObserver (it needs to not have to wait--like Recency does not--to start processing)
  // no, it shouldn't be a PreloadObserver, it should just stream from marks-reprs-pairs as they are preloaded, which
  // is what streams are supposed to do in the first place

  // TODO: 4. the real question is: how to get SearchResults working w/out waiting for data to flow through streams
  // how to prioritize branches of a stream graph












  // not using implicit Option[Long] or Boolean because it would be fairly easy to have one in scope unintentionally
  case class JoinExpiration(x: DurationMils = DEFAULT_EXPIRE_AFTER)
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
abstract class PreloadSource[+T](val loadInterval: DurationMils,
                                 bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE,
                                 asyncConsumerBoundary: Boolean = false)
                                (implicit clock: Clock, mat: Materializer)
    extends DataStream[T](bufferSize = bufferSize, asyncConsumerBoundary = asyncConsumerBoundary) {

  logger.debug(s"Constructing ${getClass.getSimpleName} (loadInterval=${loadInterval.toDays})")

  /** Pre-load a *future* block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  type PreloadCollectionType[+TT] = immutable.Seq[Datum[TT]]
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
      val ftickBuffer = fpartitionedBuffer.map(_._1.to[immutable.Seq])
      val preloadWindow = new KnownData(window.begin, window.end, ftickBuffer)

      // if there are any remaining, undistributed (future) data, put them into `buffer` for the next go-around
      buffer = Seq(fpartitionedBuffer.map(_._2.filter(_.knownTime > window.end).to[immutable.Seq]))

      preloadWindow
    }
  }

  /** Groups preloaded data into clock tick intervals and throttles it to the pace of the ticks. */
  override protected val in: SourceType[T] = {

    clock.out
      //.async.buffer(1, OverflowStrategy.backpressure) // causes entire clock to be pulled immediately
      .map { e => logger.info(s"PreloadSource: $e"); e }

      // flow ticks through the PreloadFactory which preloads (probably) big chucks of future data but then
      // only allows (probably) smaller chunks of known data to pass at each tick
      .statefulMapConcat { () =>
        val factory = PreloadFactory() // this factory is constructed once per stream materialization
        tick => immutable.Iterable(factory.knownDataFor(tick)) // lambda function called once per tick
      }

      // should only need a single thread b/c knownDataFor batches must arrive sequentially per the clock anyway,
      // the end time of the KnownData window will be that of the most recent tick (brought here by statefulMapConcat),
      // changing this from mapAsync(1) to 2 or 4 doesn't seem to have any effect prolly b/c it's Future-fast already,
      // update: this must stay at 1 b/c (a) if there are any PreloadObservers they need to call their preloads in
      // order and non-concurrently so that each call waits on the correct `queue.head` and (b) there's no need
      // to overload the database with multiple concurrent calls to our preload
      .mapAsync(1) { w: KnownData =>
        w.buffer.map { buf =>
          logger.debug(s"(\033[2m${getClass.getSimpleName}\033[0m) $w, n=${buf.size}")
          buf.toSeq.sorted(Ordering[ABV]).map { d =>
            logger.debug(s"(\033[2m${getClass.getSimpleName}\033[0m) Element: ${d.sourceTime.tfmt}, ${d.id}")
            d.copy(knownTime = w.end)
          }
        }
      }

      // mapConcat (a.k.a. flatMap) requires an immutable
      .mapConcat(identity)

      // allocate each PreloadSource its own Actor (http://blog.colinbreck.com/maximizing-throughput-for-akka-streams/)
      // there won't be many of these and they'll all typically be doing IO,
      // just have to make sure the clock doesn't slow them down
  //    .async // [PERFORMANCE]
      // update: maybe the `.async` doesn't do anything because there's already a BroadcastHub, although this buffer
      //   should keep the source flowing (as long as the clock keeps flowing)
      // update: this seems like it slows things down (considerably) by making the stream system wait until
      //   this many elements are available before batching and pushing them across the async boundary
      //.buffer(1024, OverflowStrategy.backpressure)
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
                                       bufferSize: Int = DataStream.DEFAULT_BUFFER_SIZE,
                                       asyncConsumerBoundary: Boolean = false)
                                      (implicit clock: Clock, materializer: Materializer)
    extends PreloadSource[O](subject.loadInterval, bufferSize, asyncConsumerBoundary) {

  // don't forget to observe the subject, which is the whole reason why we're here
  subject.registerPreloadObserver(this)

  // cache of previous calls to `preloadUpdate` (using TrieMap rather than faster ConcurrentHashMap b/c the former
  // has `getOrElseUpdate`)
  private[this] val cache = new scala.collection.concurrent.TrieMap[TimeStamp, Promise[PreloadCollectionType[O]]]

  /** Calls to this method are triggered by the `subject` after it performs one of its own calls to `preload`. */
  def preloadUpdate(fSubjectData: PreloadType[I], begin: TimeStamp, end: TimeStamp): Unit = {
    logger.debug(s"PreloadObserver encache (${begin.tfmt} to ${end.tfmt})")
    val fut = observerPreload(fSubjectData, begin, end)
    cache.getOrElseUpdate(end, Promise[PreloadCollectionType[O]]()).completeWith(fut)
  }

  /** Override the typical `preload` implementation with one that waits on the appropriate cache element. */
  override def preload(begin: TimeStamp, end: TimeStamp): PreloadType[O] = {
    logger.debug(s"Commence PreloadObserver wait (${begin.tfmt} to ${end.tfmt})")
    cache.getOrElseUpdate(end, Promise[PreloadCollectionType[O]]()).future.map { x =>
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
    JoinWithable(throttlee).joinWith(clock())(joiner, pairwise).asInstanceOf[SourceType[T]]
  }
}