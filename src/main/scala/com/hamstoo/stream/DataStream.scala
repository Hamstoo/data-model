package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import play.api.Logger

/**
  * A wrapper around an Akka Stream.
  * @tparam T
  */
trait DataStream[T] {

  val logger: Logger = Logger(classOf[DataStream[T]])

  /** Pure virtual Akka Source to be defined by implementation. */
  def source: Source[Datum[T], NotUsed]

}

/**
  *
  * @param source
  * @tparam T
  */
case class DataStreamRef[T](override val source: Source[Datum[T], NotUsed]) extends DataStream[T]
