/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.hamstoo.models.MarkData
import com.hamstoo.stream.Join.JoinWithable
import com.hamstoo.utils.TimeStamp
import play.api.Logger
//import spire.algebra.NRoot

import scala.reflect.{ClassTag, classTag}

/**
  * The DataStream DSL.
  */
object StreamDSL {

  /** Operations between pairs of DataStreams. */
  implicit class StreamDSL[A](private val s: DataStream[A]) extends AnyVal {

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
      implicit val ctT = asTyp
      val getter = classTag[A].runtimeClass.getDeclaredMethod(fieldName)
      s.map { a =>
        val ivk: AnyRef = getter.invoke(a)
        ivk.as[T]
      }
    }

    /** Should we enumerate a few common fields like this? */
    def timeFrom(implicit ev: ClassTag[A], m: Materializer)/*: DataStream[TimeStamp]*/ = s("timeFrom", classTag[TimeStamp])
    def timeThru(implicit ev: ClassTag[A], m: Materializer): DataStream[TimeStamp] = s("timeThru", classTag[TimeStamp])
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
    def +[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.plus(_, c.as[A]))
    def -[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.minus(_, c.as[A]))
    def *[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.times(_, c.as[A]))

    // ambiguous to have both
    //def /[C: Numeric](c: C)(implicit ev: Fractional[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.div(_, c.as[A]))
    def /[C: Numeric](c: C)(implicit ev: Numeric[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) =
      s.map(a => implicitly[Fractional[Double]].div(a.as[Double], c.as[Double]))

    def pow[C: Numeric](c: C)(implicit ev: Powable[A], m: Materializer, ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.fpow(_, c.as[A]))
  }

  /**
    * Binary operations between a LHS numeric constant and a RHS DataStream.
    */
  implicit class StreamConst[C](private val c: C) extends AnyVal {






    // TODO: can we bind all the implicits to an inner class and then make each individual operator func take that as a single implicit?
    //class Implicits(implicit val x: Int) {} // cannot extend AnyVal if this is here
    //object MyImplicits extends Implicits






    /** All of these functions return DataStream[A]s. */
    def +[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C], ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.plus(c.as[A], _))
    def -[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C], ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.minus(c.as[A], _))
    def *[A](s: DataStream[A])(implicit ev: Numeric[A], m: Materializer, evC: Numeric[C], ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.times(c.as[A], _))
    def /[A](s: DataStream[A])(implicit ev: Fractional[A], m: Materializer, evC: Numeric[C], ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.div(c.as[A], _))
    def pow[A](s: DataStream[A])(implicit ev: Powable[A], m: Materializer, evC: Numeric[C], ctA: ClassTag[A], ctC: ClassTag[C]) = s.map(ev.fpow(c.as[A], _))
  }

  /** See comment in Recency for why Spire's NRoot cannot be used in place of this typeclass. */
  trait Powable[A] { def fpow(x: A, y: A): A }
  implicit object PowableDouble extends Powable[Double] { def fpow(x: Double, y: Double): Double = math.pow(x, y) }

  /**
    * Generic implicit converters: https://hamstoo.com/my-marks/ug6JOWPLt3puWLeV
    * This doesn't seem to work.  The idea would be for Optionals to be implicitly converted to their Numeric
    * values, which could then participate in implicit StreamOps.
    */
  //implicit def optionalToValue[A](opt: OptionalInjectId[A]): A = opt.value

  /**
    * In this ridiculous class, the only allowable way to call asInstance[T] is on the unboxed/primitive version
    * of the same type (e.g. Double, which extends AnyVal) because T will be a boxed java.lang.* (e.g. java.lang.Double,
    * which extends AnyRef).  Furthermore, the only way to acquire an unboxed/primitive is by calling asInstance[Double]
    * with "Double" explicitly written; under the Scala covers, calling asInstance[F] or asInstance[T] appears to
    * resort to using ClassTag[_].runtimeClass, which is a boxed type.  And so this must happen for both types F and T.
    * If at any point along the way any boxing occurs you're screwed.
    *
    * To produce this error, comment out `.asInstance[Double]` from line 202 in the `case Long` section and run
    * FacetTests: `sbt "testOnly *FacetTests*"`.  Error message:
    *   "java.lang.ClassCastException: java.lang.Long cannot be cast to java.lang.Double"
    */
  implicit class As[F](private val v: F) extends AnyVal {
    def as[T](implicit ctA: ClassTag[F], ctT: ClassTag[T]): T = {

      //import scala.runtime.BoxesRunTime._ // unboxTo* and boxTo* which seem to be the same as asInstanceOf

      //
      ctA.runtimeClass match {
        case java.lang.Byte.TYPE => val x = v.asInstanceOf[Byte]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Short.TYPE => val x = v.asInstanceOf[Short]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Character.TYPE => val x = v.asInstanceOf[Char]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Integer.TYPE => val x = v.asInstanceOf[Int]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Long.TYPE => val x = v.asInstanceOf[Long]
          //val x = unboxedClass[java.lang.Long]().cast(a) // "Cannot cast java.lang.Long to long"

          // printed class name here should be `long` not `java.long.Double`
          Logger.debug(s"\u001b[35m As: x = ${x.getClass.getName} = $x \u001b[0m")

          val y = ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T] // this is the one that Recency uses
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }

          Logger.debug(s"\u001b[35m As: y = ${y.getClass.getName} = $y \u001b[0m")
          y

        case java.lang.Float.TYPE => val x = v.asInstanceOf[Float]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Double.TYPE => val x = v.asInstanceOf[Double]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case java.lang.Boolean.TYPE => val x = v.asInstanceOf[Boolean]
          ctT.runtimeClass match {
            case java.lang.Byte.TYPE => x.asInstanceOf[Byte].asInstanceOf[T]
            case java.lang.Short.TYPE => x.asInstanceOf[Short].asInstanceOf[T]
            case java.lang.Character.TYPE => x.asInstanceOf[Char].asInstanceOf[T]
            case java.lang.Integer.TYPE => x.asInstanceOf[Int].asInstanceOf[T]
            case java.lang.Long.TYPE => x.asInstanceOf[Long].asInstanceOf[T]
            case java.lang.Float.TYPE => x.asInstanceOf[Float].asInstanceOf[T]
            case java.lang.Double.TYPE => x.asInstanceOf[Double].asInstanceOf[T]
            case java.lang.Boolean.TYPE => x.asInstanceOf[Boolean].asInstanceOf[T]
            case _ => x.asInstanceOf[T]
          }
        case _ => v.asInstanceOf[T]
      }
    }
  }

  // this will convert a boxed/AnyRef type into it's equivalent unboxed/AnyVal, but then I don't know how
  // to use that returned value in asInstanceOf without
  //   [https://stackoverflow.com/questions/40841922/get-class-of-boxed-type-from-class-of-primitive-type]
  // scala> unboxedClass[java.lang.Double]()
  // res0: Class[_] = double
  class Unboxed[R <: AnyRef] { def apply[V <: AnyVal]()(implicit conv: R => V, ct: ClassTag[V]) = ct.runtimeClass }
  def unboxedClass[R <: AnyRef] = new Unboxed[R]
}
