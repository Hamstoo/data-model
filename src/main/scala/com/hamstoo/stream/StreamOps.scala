/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.hamstoo.stream.Join.JoinWithable

/**
  * Implicit DataStream operations.
  */
object StreamOps {

  implicit class StreamOps[A](private val s: DataStream[A]) extends AnyVal {

    /** Map a stream of Datum[A]s to Datum[O]s. */
    def map[O](f: A => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().map(_.mapValue(f))
    }

    /** Invoke JoinWithable.joinWith on the provided streams. */
    def join[B, O](oth: DataStream[B])(op: (A, B) => O)(implicit m: Materializer): DataStream[O] = new DataStream[O] {
      override def hubSource: SourceType = s().joinWith(oth())(op).asInstanceOf[SourceType]
    }

    /** The `{ case x => x }` actually does serve a purpose; it unpacks x into a 2-tuple which `identity` cannot do. */
    def pair[B](oth: DataStream[B])(implicit m: Materializer): DataStream[(A, B)] = s.join(oth) { case x => x }

    /** Use the Numeric typeclass to access binary operations. */
    def +(oth: DataStream[A])(implicit ev: Numeric[A], m: Materializer): DataStream[A] = s.join(oth)(ev.plus)















  }

}
