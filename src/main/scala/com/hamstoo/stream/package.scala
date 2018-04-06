/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.util.UUID

import akka.stream.Attributes
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType

import scala.util.Try


package object stream {

  // old verbosity levels
  //val VERBOSE_OFF = 0     // Level.INFO
  //val DISPLAY_SCORES = 1  // Level.DEBUG (for displaying scores on the website)
  //val LOG_DEBUG = 2       // Level.TRACE (for performing lots of extra logging in the logs)

  /**
    * Instructions on how to inject optional parameters with Guice.
    * https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
    */

  object LogLevelOptional { type typ = Option[ch.qos.logback.classic.Level] }
  class LogLevelOptional {
    @Inject(optional = true)
    val value: LogLevelOptional.typ = None
  }

  class Query2VecsOptional {
    @Inject(optional = true) @Named("query2Vecs")
    val value: Option[Query2VecsType] = None
  }

  class SearchLabelsOptional {
    @Inject(optional = true) @Named("labels")
    val value: Set[String] = Set.empty[String]
  }

  class SearchUserIdOptional {
    @Inject(optional = true) @Named("search.user.id")
    val value: Option[UUID] = None
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
