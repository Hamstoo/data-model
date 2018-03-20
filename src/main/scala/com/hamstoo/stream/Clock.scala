package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.stream.Tick.Tick
import com.hamstoo.utils.{DurationMils, ExtendedDurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

/**
  * A mocked clock implemented as an Akka Source.
  *
  * TODO: make interval private so that users cannot increment/decrement on their own but must instead use
  * TODO:   increment/decrement-named methods (to handle irregular intervals involving weekends and months and such)
  */
@Singleton
case class Clock @Inject() (@Named("clock.begin") begin: TimeStamp,
                            @Named("clock.end") end: TimeStamp,
                            @Named("clock.interval") interval: DurationMils)
                           (implicit materializer: Materializer) extends DataStream[TimeStamp] {

  override val logger = Logger(classOf[Clock])

  override def toString: String = s"${getClass.getSimpleName}(${begin.tfmt}, ${end.tfmt}, ${interval.dfmt})"
  logger.info(s"Constructing $this")

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
    logger.info(s"Starting $this")
    started.success {}
  }

  /** Source derived from an iterator, not a range, for one so that intervals may eventually be made irregular. */
  override protected val hubSource: Source[Tick, NotUsed] = Source.fromIterator { () =>

    new Iterator[Tick] {
      var currentTime: TimeStamp = begin

      /** Iterator protocol. */
      override def hasNext: Boolean = {
        val b = currentTime < end
        if (!b) logger.error(s"****** Clock complete") // debug
        b
      }

      /** Iterator protocol. */
      override def next(): Tick = {

        // wait for `started` to be true before ticks start incrementing
        Await.result(started.future, Duration.Inf)

        // TODO: would it ever make sense to have a clock Datum's knownTime be different from its sourceTime or val?
        logger.debug(s"*** TICK: ${currentTime.tfmt}")
        val r = currentTime
        currentTime += interval
        Tick(r)
      }
    }
  }.named("Clock")
}