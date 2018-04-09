/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.util.UUID

import akka.stream.Attributes
import com.google.inject._
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.utils.{DurationMils, TimeStamp}

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag
import scala.util.Try


package object stream {

  /**
    * Guice uses a (type, optional name) pair to uniquely identify bindings.  Instances of this class are that pair
    * _without_ the optional name.
    */
  class NamelessInjectId[T :ClassTag :TypeTag] {
    type typ = T

    /** An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal? */
    def :=(instance: T)(implicit module: StreamModule): Unit = module.assign(this, instance)
  }

  /**
    * Guice uses a (type, optional name) pair to uniquely identify bindings.  Instances of this class are that pair
    * _with_ the optional name.
    */
  trait InjectId[T] extends NamelessInjectId[T] {
    def name: String
  }

  // `final val`s are required so that the `name` values are constants that can be used in @Named annotations
  object CallingUserId extends InjectId[UUID] { final val name = "calling.user.id" }
  object Query extends InjectId[String] { final val name = "query" }
  object ClockBegin extends InjectId[TimeStamp] { final val name = "clock.begin" }
  object ClockEnd extends InjectId[TimeStamp] { final val name = "clock.end" }
  object ClockInterval extends InjectId[DurationMils] { final val name = "clock.interval" }

  /**
    * Instructions on how to inject optional parameters with Guice.
    * https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
    */

  object LogLevelOptional extends NamelessInjectId[Option[ch.qos.logback.classic.Level]]
  class LogLevelOptional {
    @Inject(optional = true)
    val value: LogLevelOptional.typ = None
  }

  object Query2VecsOptional extends InjectId[Option[Query2VecsType]] { final val name = "query2Vecs" }
  class Query2VecsOptional {
    @Inject(optional = true) @Named(Query2VecsOptional.name)
    val value: Query2VecsOptional.typ = None
  }

  object SearchLabelsOptional extends InjectId[Set[String]] { final val name = "labels" }
  class SearchLabelsOptional {
    @Inject(optional = true) @Named(SearchLabelsOptional.name)
    val value: SearchLabelsOptional.typ = Set.empty[String]
  }

  object SearchUserIdOptional extends InjectId[Option[UUID]] { final val name = "search.user.id" }
  class SearchUserIdOptional {
    @Inject(optional = true) @Named(SearchUserIdOptional.name)
    val value: SearchUserIdOptional.typ = None
  }

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
