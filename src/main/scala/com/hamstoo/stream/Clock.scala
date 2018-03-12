package com.hamstoo.stream

import akka.NotUsed
import akka.stream.{Attributes, Materializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import com.google.inject.{Inject, Singleton}
import com.hamstoo.stream.Tick.Tick
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

/**
  * A mocked clock implemented as an Akka Source.
  */
@Singleton
case class Clock @Inject() (begin: TimeStamp, end: TimeStamp, interval: DurationMils)
                           (implicit materializer: Materializer) extends DataStream[TimeStamp] {

  override val logger = Logger(classOf[Clock])
  logger.info(s"Constructing a Clock from ${begin.dt} [${begin/1000}] until ${end.dt} [${end/1000}] by ${interval.toDays} days [${interval/1000}]")

  /**
    * Since a DataStream employs an Akka BroadcastHub under the covers, the clock ticks will begin progressing as soon
    * as the first consumer is attached.  Indeed it seems the ticks begin processing immediately, even before any
    * consumers are attached, contrary to the docs: "If there are no subscribers attached to this hub then it will
    * not drop any elements but instead backpressure the upstream producer until subscribers arrive."  Either way,
    * we need to wait until the entire graph is constructed and all the consumers are hooked up before ticks start
    * incrementing.
    */
  var started = false
  def start(): Unit = {
    logger.info(s"Starting Clock(${begin/1000}, ${end/1000}, ${interval/1000})")
    started = true
  }

  override protected val hubSource: Source[Tick, NotUsed] = Source.fromIterator { () =>

    new Iterator[Tick] {
      var currentTime: TimeStamp = begin

      override def hasNext : Boolean = currentTime < end

      override def next(): Tick = {

        logger.debug(s"Tick: ${currentTime.dt} [${currentTime/1000}]")

        val r = currentTime

        // wait for `started` to be true before ticks start incrementing
        if (started) currentTime += interval else Thread.sleep(1000)

        // TODO: would it ever make sense to have a clock Datum's knownTime that is different from its sourceTime or val?
        Tick(r)
      }
    }
  }.addAttributes(Attributes.inputBuffer(initial = 1, max = 1))
    .buffer(1, OverflowStrategy.backpressure)
}