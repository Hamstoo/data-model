/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.hamstoo.models.MarkData
import com.hamstoo.stream.DataStream.{/*DisableBroadcast => DisBr,*/ JoinExpiration}
import com.hamstoo.stream.Join.JoinWithable
import com.hamstoo.utils.TimeStamp
import play.api.Logger

import scala.collection.{Traversable, immutable}
//import spire.algebra.NRoot

import scala.reflect.{ClassTag, classTag}

/**
  * The DataStream DSL.
  */
object StreamDSL {

  /** DSL for DataStreams of collection types. */
  implicit class CollectionStreamDSL[T](private val s: DataStream[Traversable[T]]) extends AnyVal {

    /** DataStreams of Traversables can be flattened. */
    def flatten(implicit m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[T] = {
      implicit val jexp: JoinExpiration = s.joinExpiration // pass this up through the dependency tree/graph
      new DataStream[T] {
        override def in: SourceType[T] = s().mapConcat { d: Datum[Traversable[T]] =>
          val values: Traversable[T] = d.value
          values.map { v: T => d.withValue[T](v) }.to[immutable.Iterable]
        }
      }
    }
  }

  /**
    * Operations between pairs of DataStreams.
    *
    * "You can avoid instantiating your extension class by making it a value class."
    *   https://stackoverflow.com/questions/40454260/is-new-instance-of-class-created-per-each-implicit-class-conversion
    */
  implicit class StreamDSL[A](private val s: DataStream[A]) extends AnyVal {

    /**
      * Map a stream of Datum[A]s to Datum[O]s.  Nearly all of the other implicit methods in this class go through
      * here, `join`, or `flatten`, which is evident from their (unique to them) constructions of `new DataStream[O]`s.
      */
    def map[O](f: A => O)(implicit m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[O] = {

      // TODO: downstream consumers of this DataStream will need to know that their data may arrive out of
      // TODO:   order if joinExpiration was set larger to accommodate a mapAsyncUnordered
      // TODO: perhaps mapAsyncUnordered is not the right way to improve efficiency then but rather
      // TODO:   the insertion of async boundaries and utilization of buffers
      implicit val jexp: JoinExpiration = s.joinExpiration

      new DataStream[O] {

        // TODO: Every time this happens a new BroadcastHub is born.  Should we instead only create the BroadcastHub
        // TODO:   if/when a DataStream gets "mapped from"/"attached to" more than once? [PERFORMANCE]
        override def in: SourceType[O] = s().map(_.mapValue(f))
      }
    }

    /** This really shouldn't be part of the interface, so just pass `ev.m` explicitly when necessary. */
    //def mapI[O](f: A => O)(implicit ev: Implicits[_, _]): DataStream[O] = s.map(f)(ev.m)

    /** Map Datum values to one of their fields (as Doubles). */
    def apply(fieldName: String)(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[Double] =
      s(fieldName, classTag[Double])

    /** Map Datum values to one of their fields (as instances of specified `asTyp` type). */
    def apply[T](fieldName: String, asTyp: ClassTag[T])(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[T] = {
      implicit val ctT: ClassTag[T] = asTyp // used in call to `as` below
      val getter = classTag[A].runtimeClass.getDeclaredMethod(fieldName)
      //val getter = typeTag[A].mirror.runtimeClass(typeTag[A].tpe).getDeclaredMethod(fieldName) // https://stackoverflow.com/questions/11494788/how-to-create-a-typetag-manually/11495793#11495793
      s.map { a =>
        val ivk: AnyRef = getter.invoke(a)
        ivk.asC[T]
      }
    }

    /** Should we enumerate a few common fields like this? */
    def timeFrom(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s("timeFrom", classTag[TimeStamp])
    def timeThru(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s("timeThru", classTag[TimeStamp])
    def mark(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s("mark", classTag[MarkData])
    def rating(implicit ev: ClassTag[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s("rating", classTag[Option[Double]])

    /** Invoke JoinWithable.joinWith on the provided streams.  Also see comment on `map`. */
    def join[B, O](that: DataStream[B])(op: (A, B) => O)(implicit m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[O] = {
      implicit val jexp: JoinExpiration = JoinExpiration(math.max(s.joinExpiration.x, that.joinExpiration.x))
      new DataStream[O] {
        override val in: SourceType[O] = s().joinWith(that())(joiner = op,
                                                              expireAfter = joinExpiration.x).asInstanceOf[SourceType[O]]
      }
    }

    /** The `{ case x => x }` actually does serve a purpose; it unpacks x into a 2-tuple, which `identity` cannot do. */
    def pair[B](that: DataStream[B])(implicit m: Materializer/*, disBr: DisBr = DisBr()*/): DataStream[(A, B)] =
      s.join(that) { case x => x }

    /** Binary operations between pairs of DataStreams (via typeclasses). */
    // TODO: why couldn't we use `that: DataStream[B]` here?  how would we select the proper `ev`?
    def +(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s.join(that)(ev.plus)
    def -(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s.join(that)(ev.minus)
    def *(that: DataStream[A])(implicit ev: Numeric[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s.join(that)(ev.times)
    def /(that: DataStream[A])(implicit ev: Fractional[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s.join(that)(ev.div)
    def pow(that: DataStream[A])(implicit ev: Powable[A], m: Materializer/*, disBr: DisBr = DisBr()*/) = s.join(that)(ev.fpow)

    /** Binary operations between a LHS DataStream and a RHS numeric constant. */
    def +[C](c: C)(implicit ev: Implicits[C, A]) = s.map(ev.nm1.plus(_, c.as[A]))(ev.m)
    def -[C](c: C)(implicit ev: Implicits[C, A]) = s.map(ev.nm1.minus(_, c.as[A]))(ev.m)
    def *[C](c: C)(implicit ev: Implicits[C, A]) = s.map(ev.nm1.times(_, c.as[A]))(ev.m)

    // ambiguous to have both, and the first one is insufficient when DataStream numerator is non-Fractional,
    // compiler error: "could not find implicit value for parameter fr: Fractional[com.hamstoo.utils.TimeStamp]"
    //def /[C](c: C)(implicit ev: Implicits[C, A], fr: Fractional[A]) = s.map(fr.div(_, c.as[A]))
    def /[C](c: C)(implicit ev: Implicits[C, A]) =
      s.map(a => implicitly[Fractional[Double]].div(a.asDouble(ev.ct1), c.asDouble(ev.ct0)))(ev.m)

    def pow[C](c: C)(implicit ev: Implicits[C, A], pw: Powable[A]) = s.map(pw.fpow(_, c.as[A]))(ev.m)
  }

  /**
    * Binary operations between a LHS numeric constant and a RHS DataStream.
    */
  implicit class StreamConst[C](private val c: C) extends AnyVal {

    /** All of these functions return DataStream[A]s. */
    def +[A](s: DataStream[A])(implicit ev: Implicits[C, A]) = s.map(ev.nm1.plus(c.as[A], _))(ev.m)
    def -[A](s: DataStream[A])(implicit ev: Implicits[C, A]) = s.map(ev.nm1.minus(c.as[A], _))(ev.m)
    def *[A](s: DataStream[A])(implicit ev: Implicits[C, A]) = s.map(ev.nm1.times(c.as[A], _))(ev.m)
    def /[A](s: DataStream[A])(implicit ev: Implicits[C, A]) =
      s.map(a => implicitly[Fractional[Double]].div(c.asDouble(ev.ct0), a.asDouble(ev.ct1)))(ev.m)
    def pow[A](s: DataStream[A])(implicit ev: Implicits[C, A], pw: Powable[A]) = s.map(pw.fpow(c.as[A], _))(ev.m)
  }

  /** See comment in Recency for why Spire's NRoot cannot be used in place of this typeclass. */
  trait Powable[A] { def fpow(x: A, y: A): A }
  implicit object PowableDouble extends Powable[Double] { def fpow(x: Double, y: Double): Double = math.pow(x, y) }

  /**
    * Bind all the implicits inside a wrapper so that individual operator functions need only take a single
    * implicit parameter.
    */
  @scala.annotation.implicitNotFound(msg = "No StreamDSL.Implicits available for [${_0}, ${_1}]")
  case class Implicits[_0, _1](ct0: ClassTag[_0], ct1: ClassTag[_1], nm0: Numeric[_0], nm1: Numeric[_1],
                               m: Materializer/*, disBr: DisBr = DisBr()*/)

  /** `implicit` Implicits factory function. */
  implicit def implicits[_0, _1](implicit ct0: ClassTag[_0], ct1: ClassTag[_1], nm0: Numeric[_0], nm1: Numeric[_1],
                                 m: Materializer/*, disBr: DisBr = DisBr()*/): Implicits[_0, _1] =
    Implicits(ct0, ct1, nm0, nm1, m/*, disBr*/)

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
    *
    * @param v   The value to be type casted to a T, the "to" type.
    * @tparam F  The "from" type being casted from.
    */
  protected implicit class As[F](private val v: F) extends AnyVal {

    /** Extract ClassTags from Implicits wrapper. */
    def as[T](implicit ev: Implicits[F, T]): T = v.asC[T](ev.ct0, ev.ct1)

    def asDouble(implicit ctF: ClassTag[F]): Double = asC(ctF, classTag[Double])

    /** Ridiculous. */
    def asC[T](implicit ctF: ClassTag[F], ctT: ClassTag[T]): T = {

      //import scala.runtime.BoxesRunTime._ // unboxTo* and boxTo* which seem to be the same as asInstanceOf

      //
      ctF.runtimeClass match {
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

  /**
    * This will convert a boxed/AnyRef Java type into it's equivalent unboxed/AnyVal Scala type, but then I don't
    * know how to use that returned value in asInstanceOf without inadvertently switching back to the boxed/AnyRef.
    *   [https://stackoverflow.com/questions/40841922/get-class-of-boxed-type-from-class-of-primitive-type]
    *
    * {{{
    *   scala> unboxedClass[java.lang.Double]()
    *   res0: Class[_] = double
    * }}}
    */
  //class Unboxed[R <: AnyRef] { def apply[V <: AnyVal]()(implicit conv: R => V, ct: ClassTag[V]) = ct.runtimeClass }
  //def unboxedClass[R <: AnyRef] = new Unboxed[R]
}
