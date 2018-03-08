package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.hamstoo.utils.{DurationMils, TimeStamp}

/**
  * A mocked clock implemented as an Akka Source.
  */
case class Clock(start: TimeStamp, stop: TimeStamp, interval: DurationMils) extends DataStream[TimeStamp] {

  override val source: Source[Data[TimeStamp], NotUsed] = Source.fromIterator { () =>

    new Iterator[Data[TimeStamp]] {
      var currentTime: TimeStamp = start

      override def hasNext : Boolean = currentTime < stop

      override def next(): Data[TimeStamp] = {
        val r = currentTime
        currentTime += interval

        // TODO: would it ever make sense to have a clock Datum's knownTime that is different from its sourceTime or val?
        Datum[TimeStamp](UnitId(), r, r)
      }
    }
  }
}