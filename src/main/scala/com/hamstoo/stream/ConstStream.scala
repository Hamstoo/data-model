package com.hamstoo.stream

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.hamstoo.stream.Data.Data

/**
  * Repeatedly emit the same constant value whenever there is demand.
  *
  * TODO: This class is unnecessary given that the StreamDSL allows binary operations with constants.
  * TODO: Instead, we should just amend the StreamDSL to work w/ OptionalInjectIds.
  * TODO:    (see comment to the same effect in FacetsModel.Default)
  */
class ConstStream[T]()(implicit mat: Materializer) extends DataStream[T] {

  /**
    * Auxiliary constructor 1.
    * @param k  The constant.
    */
  def this(k: T)(implicit mat: Materializer) = {
    this()
    data = () => Data[T](Datum[T](k, UnitId, 0L)) // TODO: won't Join immediately drop this Datum b/c timestamp expired
  }

  /**
    * Auxiliary constructor 2.
    * @param name     Dependency injection key name.
    * @param default  Dependency injection default value reference, which also determines key type.  This default
    *                 value can be overridden via the dependency injection optional binding mechanism.
    */
  def this(name: String, default: => T = null)(implicit mat: Materializer, mfest: Manifest[T]) = {
    this()
    implicit val mf: Manifest[T] = mfest // TODO: why can't this just be supplied to OptionalInjectId explicitly?
    data = () => Data[T](Datum[T](new OptionalInjectId[T](name, default).value, UnitId, 0L))
  }

  // this must be a reference to a Data[T], rather than a Data[T] itself, so that it can be injected, if desired
  private var data: () => Data[T] = _

  // cache it so that we don't have to call `data` more than once
  private var cached: Data[T] = _
  private def dataCached(): Data[T] = {
    if (cached != null)
      cached = data()
    cached
  }

  override val in: SourceType = Source.repeat(dataCached())
}