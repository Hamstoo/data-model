/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.util.UUID

import akka.stream.Attributes
import com.google.inject._
import com.google.inject.name.Names
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.utils.{DurationMils, TimeStamp}
import net.codingwell.scalaguice.typeLiteral

import scala.util.Try


package object stream {

  /**
    * Guice uses a (type, optional name) pair to uniquely identify bindings.  Instances of this class are that pair
    * _without_ the optional name.
    *
    * Note that this _will_ compile with `T :ClassTag :TypeTag` context bounds, but the Guice TypeLiteral that is
    * constructed during the call to `assignOptional` will be missing type information in that case, regardless
    * of whether `scalaguice.typeLiteral[T]` is used or the more Java'ish `new TypeLiteral[T]() {}`.  This is why
    * we use the older Scala version's `T :Manifest` context bound instead, which is what scala-guice uses also.
    *
    * See also: `public static <T> Key[T] get(`type`: Class[T])` in Key.java
    *   In other words, Guice already has this concept, but the name value can't be `final`/constant.
    */
  class NamelessInjectId[T :Manifest] {
    type typ = T

    /** An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal? */
    def :=(instance: T)(implicit module: StreamModule): Unit = module.assign(key, instance)
    def ?=(default: T)(implicit module: StreamModule): Unit = module.assignOptional(key, default)

    /**
      * Guice is a Java package so it uses its own version of a Manifest/ClassTag/TypeTag called a TypeLiteral,
      * and scala-guice defines the corresponding classOf/typeOf factory function called typeLiteral.
      */
    def key: Key[T] = Key.get(typeLiteral[T])
  }

  /**
    * Guice uses a (type, optional name) pair to uniquely identify bindings.  Instances of this class are that pair
    * _with_ the optional name.
    *
    * See also: `static <T> Key<T> get(Class<T> type, AnnotationStrategy annotationStrategy)` in Key.java
    *   In other words, Guice already has this concept, but the name value can't be `final`/constant.
    */
  abstract class InjectId[T: Manifest] extends NamelessInjectId[T] {
    def name: String
    override def key: Key[T] = Key.get(typeLiteral[T], Names.named(name))
  }

  // `final val`s are required so that their values are constants that can be used at compile time in @Named annotations
  object CallingUserId extends InjectId[UUID] { final val name = "calling.user.id" }
  object Query extends InjectId[String] { final val name = "query" }
  object ClockBegin extends InjectId[TimeStamp] { final val name = "clock.begin" }
  object ClockEnd extends InjectId[TimeStamp] { final val name = "clock.end" }
  object ClockInterval extends InjectId[DurationMils] { final val name = "clock.interval" }

  object LogLevelOptional extends NamelessInjectId[Option[ch.qos.logback.classic.Level]]
  object Query2VecsOptional extends InjectId[Option[Query2VecsType]] { final val name = "query2Vecs" }
  object SearchLabelsOptional extends InjectId[Set[String]] { final val name = "labels" }
  object SearchUserIdOptional extends InjectId[Option[UUID]] { final val name = "search.user.id" }

  /** One might think that getting the name of a stream would be easier than this. */
  def streamName[S](stream: S): String = {

    import scala.language.reflectiveCalls

    // TraveralBuilder is a private[akka] type so we need to use a generic type parameter T here instead
    type Duck[T] = { val traversalBuilder: T /*akka.stream.impl.TraversalBuilder*/ }

    // it's unclear whether or not this might throw an exception in some cases which is why it's wrapped in a Try
    // more here: https://stackoverflow.com/questions/1988181/pattern-matching-structural-types-in-scala
    val x: Option[Attributes.Name] = Try {
      stream match {
        //case simp: Source[Data[A0], Mat] => simp.traversalBuilder.attributes.get[Attributes.Name]
        //case fimp: Flow[In, Data[A0], Mat] => fimp.traversalBuilder.attributes.get[Attributes.Name]
        case duck: Duck[{ def attributes: Attributes }] => duck.traversalBuilder.attributes.get[Attributes.Name]
        case _ => None // make it a total function to avoid MatchErrors
      }
    }.getOrElse(None)

    x.fold("<noname>")(_.n)
  }
}
