/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.hamstoo.stream.Join.JoinWithable

import scala.reflect.{ClassTag, classTag}

/**
  * Implicit DataStream operations.
  */
object StreamOps {

  /** Implicit conversion of a constant value to a DataStream. */
  /*implicit def numericToStream[A](a: A)(implicit clock: Clock): DataStream[A] = new DataStream[A] {

    // TODO: does this mean that Join needs to join based on knownTime?  i just changed it to use sourceTime!
    // TODO:   o/w joining 2 data streams would require knowing both of their sourceTime frequencies
    override def hubSource: SourceType = clock().map(_.mapValue(_ => a))



    // TODO: use Source.single here instead????? to avoid anything having to do w/ clock



  }*/

  /** Generic implicit converters: https://hamstoo.com/my-marks/ug6JOWPLt3puWLeV */
  //implicit def optionalToValue[A <: OptionalInjectId[A]](opt: A): A = opt.value

  /**  */
  //implicit def streamToDoubleStream[A](s: DataStream[A]): DataStream[Double] = s.map(_.asInstanceOf[Double])

  /** Operations between pairs of DataStreams. */
  implicit class StreamOps[A](private val s: DataStream[A]) extends AnyVal {

    /** Map a stream of Datum[A]s to Datum[O]s. */
    def map[O](f: A => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().map(_.mapValue(f))
    }

    /** Map Datum values to one of their fields (as Doubles). */
    def apply(fieldName: String)(implicit ev: ClassTag[A], m: Materializer): DataStream[Double] = {
      val field = classTag[A].runtimeClass.getField(fieldName)
      s.map(a => field.get(a).asInstanceOf[Double])
    }

    /** Map Datum values to one of their fields (as instances of specified `asTyp` type). */
    def apply[T](fieldName: String, asTyp: ClassTag[T])(implicit ev: ClassTag[A], m: Materializer): DataStream[T] = {
      val field = classTag[A].runtimeClass.getField(fieldName)
      s.map(a => asTyp.unapply(field.get(a)).get)
    }

    /** Invoke JoinWithable.joinWith on the provided streams. */
    def join[B, O](that: DataStream[B])(op: (A, B) => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().joinWith(that())(op).asInstanceOf[SourceType]
    }

    /** The `{ case x => x }` actually does serve a purpose; it unpacks x into a 2-tuple which `identity` cannot do. */
    def pair[B](that: DataStream[B])(implicit m: Materializer): DataStream[(A, B)] = s.join(that) { case x => x }

    /** Use the Numeric and Fractional typeclasses to access binary operations between pairs of DataStreams. */
    def +(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.plus)
    def -(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.minus)
    def *(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.join(that)(ev.times)
    def /(that: DataStream[A])(implicit ev: Fractional[A], m: Materializer) = s.join(that)(ev.div)

    /** Binary operations between a LHS DataStream and a RHS constant. */
    def +[C](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.plus(_, c.asInstanceOf[A]))
    def -[C](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.minus(_, c.asInstanceOf[A]))
    def *[C](c: C)(implicit ev: Numeric[A], m: Materializer) = s.map(ev.times(_, c.asInstanceOf[A]))
    def /[C](c: C)(implicit ev: Fractional[A], m: Materializer) = s.map(ev.div(_, c.asInstanceOf[A]))


  }

  // tried doing it like this first, with typeclasses to for both LHS and RHS constants
  /*
  /** Typeclass for operating with a DataStream. */
  trait OpStream[A] {
    def +(a: A, s: DataStream[A]): DataStream[A]
    def +(s: DataStream[A], a: A): DataStream[A]
    def -(a: A, s: DataStream[A]): DataStream[A]
    def -(s: DataStream[A], a: A): DataStream[A]
  }

  /** OpStream instance for typeclass Fractional.  Does anyone call this a "specialization" anymore? */
  implicit def fractionalOpStream[A](implicit ev: Fractional[A], m: Materializer): OpStream[A] = new OpStream[A] {
    def +(a: A, s: DataStream[A]): DataStream[A] = s.map(ev.plus(a, _))
    def +(s: DataStream[A], a: A): DataStream[A] = s.map(ev.plus(_, a))
    def -(a: A, s: DataStream[A]): DataStream[A] = s.map(ev.minus(a, _))
    def -(s: DataStream[A], a: A): DataStream[A] = s.map(ev.minus(_, a))
  }
  */

  // then tried this with implicit conversion of any constant to a StreamConst type... followed by a typeclass
  /*
  case class StreamConst[T](k: T)
  implicit def streamConst[T](k: T): StreamConst[T] = StreamConst(k)

  trait OpStream[A] {
    def +(a: StreamConst[A], s: DataStream[A]): DataStream[A]
    def +(s: DataStream[A], a: StreamConst[A]): DataStream[A]
    def -(a: StreamConst[A], s: DataStream[A]): DataStream[A]
    def -(s: DataStream[A], a: StreamConst[A]): DataStream[A]
  }

  implicit def fractionalOpStream[A](implicit ev: Fractional[A], m: Materializer): OpStream[A] = new OpStream[A] {
    def +(a: StreamConst[A], s: DataStream[A]): DataStream[A] = s.map(ev.plus(a.k, _))
    def +(s: DataStream[A], a: StreamConst[A]): DataStream[A] = s.map(ev.plus(_, a.k))
    def -(a: StreamConst[A], s: DataStream[A]): DataStream[A] = s.map(ev.minus(a.k, _))
    def -(s: DataStream[A], a: StreamConst[A]): DataStream[A] = s.map(ev.minus(_, a.k))
  }
  */

  /**
    * Binary operations between a LHS constant and a RHS DataStream.
    */
  implicit class StreamConst[C](private val c: C) extends AnyVal {

    /** All of these functions return DataStream[A]s. */
    def +[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.map(ev.plus(c.asInstanceOf[A], _))
    def -[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.map(ev.minus(c.asInstanceOf[A], _))
    def *[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer) = s.map(ev.times(c.asInstanceOf[A], _))
    def /[A](s: DataStream[A])(implicit ev: Fractional[A], m: Materializer) = s.map(ev.div(c.asInstanceOf[A], _))

  }


}
