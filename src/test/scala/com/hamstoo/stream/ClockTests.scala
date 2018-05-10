/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.scaladsl.{Sink, Source}
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{DurationMils, ExtendedTimeStamp, TimeStamp}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * ClockTests
  */
class ClockTests
  extends AkkaMongoEnvironment("ClockTests-ActorSystem")
    with org.scalatest.mockito.MockitoSugar
    with FutureHandler {

  val logger = Logger(classOf[ClockTests])

  "Clock" should "throttle a PreloadSource" in {

    // https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely
    // this doesn't print anything when lower than 255 (due to bufferSize!)
    /*val source = Source(0 to 255)
      .map { n => if (n % 10 == 0) println(s"*** tick $n"); n }
      .runWith(BroadcastHub.sink[Int](bufferSize = 256))
    source.runForeach { n => Thread.sleep(1); if (n % 10 == 0) println(s"------------- source1: $n") }
    source.runForeach(n => if (n % 10 == 0) println(s"\033[37msource2\033[0m: $n"))*/

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    // changing this interval should not affect the results (even if it's fractional)
    val preloadInterval = 3 days

    implicit val ec: ExecutionContext = system.dispatcher
    case class TestSource() extends PreloadSource[TimeStamp](preloadInterval.toMillis) {
      //override val logger = Logger(classOf[TestSource]) // this line causes a NPE for some reason
      override def preload(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[TimeStamp]]] = Future {

        // must snapBegin, o/w DataSource's preloadInterval can affect the results, which it should not
        val dataInterval = (12 hours).toMillis
        val snapBegin = ((begin - 1) / dataInterval + 1) * dataInterval

        val x = (snapBegin until end by dataInterval).map { t =>
          logger.debug(s"preload i: ${t.tfmt}")
          Datum[TimeStamp](t, MarkId("je"), t)
        }
        logger.debug(s"preload end: n=${x.length}")
        x
      }
    }

    val fut: Future[Int] = TestSource().out
      .map { e => logger.debug(s"TestSource: ${e.knownTime.tfmt} / ${e.sourceTime.tfmt} / ${e.value.dt.getDayOfMonth}"); e }
      .runWith(
        Sink.fold[Int, Datum[TimeStamp]](0) { case (agg, d) => agg + d.value.dt.getDayOfMonth }
      )

    clock.start()

    val x = Await.result(fut, 10 seconds)
    x shouldEqual 173
  }

  // TODO: is this test nondeterministic now that the clock's bufferSize is > 1 (and `.async`)?
  //       (see comment on DataStream.DEFAULT_BUFFER_SIZE)
  it should "throttle a ThrottledSource (with 4 Datums between each clock tick)" in {

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    implicit val ec: ExecutionContext = system.dispatcher
    case class TestSource() extends ThrottledSource[TimeStamp]() {

      // this doesn't work with a `val` (NPE) for some reason, but it works with `lazy val` or `def`
      override lazy val throttlee: SourceType[TimeStamp] = Source {

        // no need to snapBegin like with PreloadSource test b/c peeking outside of the class for when to start
        val dataInterval = (12 hours).toMillis

        // a preload source would backup the data until the (exclusive) beginning of the tick interval before `start`,
        // so that's what we do here with `start - interval + dataInterval` to make the two test results match up
        (start - interval + dataInterval until stop by dataInterval).map { t => Datum[TimeStamp](t, MarkId("kf"), t) }
      }.named("TestThrottledSource")
    }

    val fut: Future[Int] = TestSource().out
      .map { e => logger.debug(s"TestSource: ${e.knownTime.tfmt} / ${e.sourceTime.tfmt} / ${e.value.dt.getDayOfMonth}"); e }
      .runWith(
        Sink.fold[Int, Datum[TimeStamp]](0) { case (agg, d) => agg + d.value.dt.getDayOfMonth }
      )

    Thread.sleep(5000)
    clock.start()

    val x = Await.result(fut, 10 seconds)
    x shouldEqual 173
  }
}