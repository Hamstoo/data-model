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
  def source: Source[Data[T], NotUsed]

}

/**
  * A DataSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.
  */
abstract class DataSource[T](implicit clock: Clock) extends DataStream[T] {

  /** Load a chunk or a block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  def load(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[T]]]

  /** Calls abstract `load` for each consecutive pair of clock ticks. */
  override val source: Source[Data[T], NotUsed] =
    clock.sliding(2, 1) // pairwise
      .mapAsync(1) { case b :: e :: Nil => load(b, e) }
      .mapConcat(identity) // https://www.beyondthelines.net/computing/akka-streams-patterns/
}


/*

1. each variable registers itself in the cache/Injector as a materialized broadcast hub
2. the variables are singletons so when they are requested the Injector provides the same instance to all
3. each time a variable is registered it is with a clock start offset differential (requires `extends Injector`)
  3b. the max offset differential is tracked for each variable
  3c. this is when the variable needs to start paying attention to clock ticks and streaming its data
4. the clock is started with the max of all differentials
5. all variables listen to the clock (itself a BroadcastHub)
6. variables have their own frequencies, independent of the clock
  6b. if they load data faster than the clock then more than one slice gets emitted each clock tick
  6c. if they load data slower than the clock then no new data could be emitted at some ticks


I. can the implementation of a function be mangled into a checksum? for versioning/dependency purposes
  - see: https://hamstoo.com/my-marks/KnwYcpMv4OQxoNAI





 */
