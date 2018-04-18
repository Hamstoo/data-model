/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.hamstoo.models.MarkData
import com.hamstoo.stream.Join.JoinWithable
//import spire.algebra.NRoot

import scala.reflect.{ClassTag, classTag}

/**
  * The DataStream DSL.
  */
object StreamOps {

  /** Operations between pairs of DataStreams. */
  implicit class StreamOps[A](private val s: DataStream[A]) extends AnyVal {

    /** Map a stream of Datum[A]s to Datum[O]s. */
    def map[O](f: A => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().map(_.mapValue(f))
    }

    /** Map Datum values to one of their fields (as Doubles). */
    def apply(fieldName: String)(implicit ev: ClassTag[A], m: Materializer): DataStream[Double] = {
      //val field = classTag[A].runtimeClass.getField(fieldName)
      //s.map(a => field.get(a).asInstanceOf[Double])
      s(fieldName, classTag[Double])
    }

    /** Map Datum values to one of their fields (as instances of specified `asTyp` type). */
    def apply[T](fieldName: String, asTyp: ClassTag[T])(implicit ev: ClassTag[A], m: Materializer): DataStream[T] = {
      val field = classTag[A].runtimeClass.getField(fieldName)
      s.map(a => asTyp.unapply(field.get(a)).get)
    }

    /** Should we enumerate a few common fields like this? */
    def timeFrom(implicit ev: ClassTag[A], m: Materializer): DataStream[Double] = s("timeFrom")
    def timeThru(implicit ev: ClassTag[A], m: Materializer): DataStream[Double] = s("timeThru")
    def mark(implicit ev: ClassTag[A], m: Materializer): DataStream[MarkData] = s("timeThru", classTag[MarkData])

    /** Invoke JoinWithable.joinWith on the provided streams. */
    def join[B, O](that: DataStream[B])(op: (A, B) => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().joinWith(that())(op).asInstanceOf[SourceType]
    }

    /** The `{ case x => x }` actually does serve a purpose; it unpacks x into a 2-tuple, which `identity` cannot do. */
    def pair[B](that: DataStream[B])(implicit m: Materializer): DataStream[(A, B)] = s.join(that) { case x => x }

    /** Binary operations between pairs of DataStreams (via typeclasses). */
    // TODO: why couldn't we use `that: DataStream[B]` here?  how would we select the proper `ev`?
    def +(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.plus)
    def -(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.minus)
    def *(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.times)
    def /(that: DataStream[A])(implicit ev: Fractional[A], m: Materializer) = s.join(that)(ev.div)
    def pow(that: DataStream[A])(implicit ev: Powable[A], m: Materializer) = s.join(that)(ev.fpow)

    /** Binary operations between a LHS DataStream and a RHS numeric constant. */
    def +[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.plus(_, c.asInstanceOf[A]))
    def -[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.minus(_, c.asInstanceOf[A]))
    def *[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.times(_, c.asInstanceOf[A]))
    def /[C: Numeric](c: C)(implicit ev: Fractional[A], m: Materializer) = s.map(ev.div(_, c.asInstanceOf[A]))
    def pow[C: Numeric](c: C)(implicit ev: Powable[A], m: Materializer) = s.map(ev.fpow(_, c.asInstanceOf[A]))
  }

  /**
    * Binary operations between a LHS numeric constant and a RHS DataStream.
    */
  implicit class StreamConst[C](private val c: C) extends AnyVal {

    /** All of these functions return DataStream[A]s. */
    def +[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C]) = s.map(ev.plus(c.asInstanceOf[A], _))
    def -[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C]) = s.map(ev.minus(c.asInstanceOf[A], _))
    def *[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C]) = s.map(ev.times(c.asInstanceOf[A], _))
    def /[A](s: DataStream[A])(implicit ev: Fractional[A], m: Materializer, evC: Numeric[C]) = s.map(ev.div(c.asInstanceOf[A], _))
    def pow[A](s: DataStream[A])(implicit ev: Powable[A], m: Materializer, evC: Numeric[C]) = s.map(ev.fpow(c.asInstanceOf[A], _))
  }

  /** See comment in Recency for why Spire's NRoot cannot be used in place of this typeclass. */
  trait Powable[A] { def fpow(x: A, y: A): A }
  implicit object PowableDouble extends Powable[Double] {
    def fpow(x: Double, y: Double): Double = math.pow(x, y)
  }

  /**
    * Generic implicit converters: https://hamstoo.com/my-marks/ug6JOWPLt3puWLeV
    * This doesn't seem to work.  The idea would be for Optionals to be implicitly converted to their Numeric
    * values, which could then participate in implicit StreamOps.
    */
  //implicit def optionalToValue[A](opt: OptionalInjectId[A]): A = opt.value
}
