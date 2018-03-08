package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.hamstoo.utils.{DurationMils, TimeStamp}

import scala.collection.immutable
import scala.concurrent.Future

/**
  * A wrapper around an Akka Stream.
  */
trait DataStream[T] {

  // TODO: A Singleton DataStream will need a broadcast hub, which will need to be created upon construction
  // TODO:  as triggered by Guice
  //val hubSource = // TODO: materialize hubSource into a hub

  /** Pure virtual Akka Source to be defined by implementation. */
  // TODO: rename -> `stream`
  def source: Source[Data[T], NotUsed]

}

/**
  * A DataSource is merely a DataStream that can listen to a Clock so that it knows when to load
  * data from its abstract source.  It throttles its stream emissions to 1/`period` frequency.
  */
abstract class DataSource[T](implicit clock: Clock) extends DataStream[T] {

  /** Load a chunk or a block of data from the data source.  `begin` should be inclusive and `end`, exclusive. */
  def load(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[T]]]

  // TODO: change to be a clock-throttled stream rather than calling `load` between consecutive ticks
  // TODO:   this will allow a source to load at its own rate and merely be emitted at whatever rate is desired
  //def period: DurationMils = 1

  /** Calls abstract `load` for each consecutive pair of clock ticks. */
  override val source: Source[Data[T], NotUsed] =
    clock.source.sliding(2, 1) // pairwise
      //.map(_.toList) // convert from Vector to List for the following case statement [https://stackoverflow.com/questions/11503033/what-is-the-idiomatic-way-to-pattern-match-sequence-comprehensions/11503173#11503173]
      .mapAsync(1) { case Seq(b, e/*, _rest @ _ * */) /*b :: e :: Nil*/ => load(b.oval.get.value, e.oval.get.value) }
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
