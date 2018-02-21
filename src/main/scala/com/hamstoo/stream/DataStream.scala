package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.hamstoo.stream.Clock.Clock
import com.hamstoo.utils.TimeStamp
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.Future

/**
  * A wrapper around an Akka Stream.
  */
abstract class DataStream[T] {

  val logger: Logger = Logger(classOf[DataStream[T]])

  /** Pure virtual Akka Source to be defined by implementation. */
  def source: Source[Datum[T], NotUsed]

}

/**
  * A DataSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.
  */
abstract class DataSource[T](implicit clock: Clock) extends DataStream[T] {

  /** Load a chunk or a block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  def load(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[T]]]

  /** Calls abstract `load` for each consecutive pair of clock ticks. */
  override val source: Source[Datum[T], NotUsed] =
    clock.sliding(2, 1)
      .mapAsync(1) { case b :: e :: Nil => load(b, e) }
      .mapConcat(identity) // https://www.beyondthelines.net/computing/akka-streams-patterns/
}