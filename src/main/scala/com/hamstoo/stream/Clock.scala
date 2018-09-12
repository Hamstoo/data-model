/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.pattern.after
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TIME_NOW, TimeStamp}
import org.joda.time.DateTime

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Try}

/**
  * A mocked clock, implemented as an Akka Source.
  */
@com.google.inject.Singleton
case class Clock(begin: TimeStamp, end: TimeStamp, private val interval: DurationMils)
                (implicit mat: ActorMaterializer)

    extends ElemStream[Datum[TimeStamp]](bufferSize = 1) {
      // we use 1 here because there appears to be a bug in BroadcastHub where if the buffer consumes the
      // producer before any flows/sinks are attached to the materialized hub the producer will stop and the
      // dependent flows/sinks won't ever have a chance to backpressure (which is a problem for the clock especially!)
      //   [https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely]

  /** Dependency injection constructor--there can be only one.  Convert from OptionalInjectIds to their values. */
  @Inject def this(beginOpt: Clock.BeginOptional, endOpt: Clock.EndOptional, intervalOpt: Clock.IntervalOptional)
                  (implicit mat: ActorMaterializer) =
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
  val started: Promise[Unit] = Promise()
  def start(): Unit = {
    logger.info(s"\033[33mStarting $this\033[0m")
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
        if (!bool) logger.info(s"\033[33mClock complete\033[0m")
        bool
      }

      /** Iterator protocol. */
      override def next(): Tick = {

        // TODO: are these blocking Awaits causing the deadlock in MarksStream?
        // TODO:   if, for example, MarksStream gets assigned the same thread as the Clock while it's blocking

        // wait for `started` to be true before ticks start ticking
        if (!started.future.isCompleted)
          Await.result(started.future, Duration.Inf)

        // wait for all clock observers to receive their ticks before ticking again (issue #340)
        def waitForClockObsvrs(nAttempts: Int = 10): Future[Unit] = {

          val fSuccess = Future.sequence(clockObsvrsWithCompletedTicks.values.map(_.future)).map(_ => ())

          // https://stackoverflow.com/questions/47574526/scala-how-to-use-a-timer-without-blocking-on-futures-with-await-result
          lazy val ex = new TimeoutException({
            val uf = clockObsvrsWithCompletedTicks.filterNot(_._2.future.isCompleted).keys.map(_.getClass.getSimpleName)
            s"Clock timed out waiting for observers to receive ticks; unfinished observers: $uf"
          })
          lazy val fTimeout = after(duration = 98 millis, using = mat.system.scheduler)(Future.failed(ex))

          // why doesn't this work the same as what's below?  the recoverWith doesn't seem to ever be invoked; the
          // exception gets passed straight to the Akka supervisor instead:
          //   > [ERROR] [09/11/2018 19:35:09.643] [aux-system-akka.actor.default-dispatcher-8]
          //   > [akka://aux-system/system/StreamSupervisor-0/flow-0-0-unnamed]
          //   > Error in stage [StatefulMapConcat]: Futures timed out after [9 milliseconds]
          //Await.ready(f, 9 millis).recoverWith { case _ => waitForClockObsvrs() }

          val result = Promise[Unit]()

          Future.firstCompletedOf(Seq(fSuccess, fTimeout)).onComplete {
          //Try(Await.result(f, 49 millis)) match {
            case Success(_) => result.success {}
            case Failure(_) if nAttempts > 0 =>
              val n = clockObsvrsWithCompletedTicks.values.count(_.future.isCompleted)
              logger.info(s"Waiting on clock observers (nComplete=$n/${clockObsvrs.size})")
              result.completeWith(waitForClockObsvrs(nAttempts = nAttempts - 1))
            case Failure(t) =>
              logger.error(t.getMessage)
              result.failure(t)
          }

          result.future
        }

        Await.result(waitForClockObsvrs(), Duration.Inf)
        clockObsvrsWithCompletedTicks.clear()
        clockObsvrsWithCompletedTicks ++= clockObsvrs.map(_ -> Promise[Unit]())

        // would it ever make sense to have a clock Datum's knownTime be different from its sourceTime or val?
        val previousTime = currentTime
        currentTime = math.min(currentTime + interval, end) // ensure we don't go beyond `end`
        logger.debug(s"\033[33mTICK: ${currentTime.tfmt}\033[0m")
        Tick(currentTime, previousTime)
      }
    }
  }.named("Clock")

  // data structures to ensure BroadcastHub doesn't forget to send messages to any of its spokes (issue #340)
  private val clockObsvrsWithCompletedTicks = new scala.collection.concurrent.TrieMap[PreloadSource[_], Promise[Unit]]
  private val clockObsvrs = mutable.Set.empty[PreloadSource[_]]

  /** Same as base class' apply but with an observer argument, which gets added to the clock's observer set. */
  def apply(clockObsvr: PreloadSource[_]): SourceType = {
    clockObsvrs += clockObsvr
    this.out
  }

  /** Called by PreloadSource once it's had a chance to process the current clock tick. (issue #340) */
  def tickCompleteFor(clockObsvr: PreloadSource[_]): Unit = {
    //clockObsvrsWithCompletedTicks.getOrElseUpdate(clockObsvr, Promise[Unit]()).completeWith(Future.unit)
    clockObsvrsWithCompletedTicks.getOrElseUpdate(clockObsvr, Promise[Unit]()).success {}
    val n = clockObsvrsWithCompletedTicks.values.count(_.future.isCompleted)
    logger.debug(s"Tick completed for '${clockObsvr.getClass.getSimpleName}' (nComplete=$n/${clockObsvrs.size})")
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
