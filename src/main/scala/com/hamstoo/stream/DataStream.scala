package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import play.api.Logger

/**
  * A wrapper around an Akka Stream.
  */
abstract class DataStream[T](implicit clock: Clock) {

  val logger: Logger = Logger(classOf[DataStream[T]])

  /** Pure virtual Akka Source to be defined by implementation. */
  def source: Source[Datum[T], NotUsed]

}

/**
  * A DataStream that is composed of a source provided by elsewhere.  For example, a stream that computes
  * a function of one or more other streams may, as its last step, wrap up its resulting stream in a DataStreamRef.
  * A more proper name perhaps would be DataStreamFunction.
  * @param source  Outside provided Akka Source
  */
case class DataStreamRef[T](override val source: Source[Datum[T], NotUsed])(implicit clock: Clock) extends DataStream[T]
