/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.util.UUID

import akka.stream.Attributes
import com.google.inject.{Inject, Key}
import com.google.inject.name.{Named, Names}
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.stream.config.BaseModule
import com.hamstoo.utils.{DurationMils, TimeStamp}
import net.codingwell.scalaguice.typeLiteral
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
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
    *   In other words, Guice already has this InjectId concept, but the name value can't be made `final`/constant.
    */
  class NamelessInjectId[T :Manifest] {
    type typ = T

    /** An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal? */
    def :=(instance: typ)(implicit module: BaseModule): Unit = module.assign(key, instance)
    def ?=(default: typ)(implicit module: BaseModule): Unit = module.assignOptional(key, default)

    /**
      * Guice is a Java package so it uses its own (Java) version of a Manifest/ClassTag/TypeTag called a TypeLiteral,
      * and the scala-guice package defines the corresponding classOf/typeOf factory function called typeLiteral.
      */
    val key: Key[typ] = Key.get(typeLiteral[typ])
    if (key != null) Logger.debug(s"${getClass.getSimpleName}($key)")
  }

  /**
    * Guice uses a (type, optional name) pair to uniquely identify bindings.  Instances of this class are that pair
    * _with_ the optional name.
    *
    * See also: `static <T> Key<T> get(Class<T> type, AnnotationStrategy annotationStrategy)` in Key.java
    *   In other words, Guice already has this concept, but the name value can't be made `final`/constant.
    */
  abstract class /*RichKey*/InjectId[T :Manifest] extends NamelessInjectId[T] {
    def name: String
    override val key: Key[typ] = Key.get(typeLiteral[typ], Names.named(name))
    if (key != null) Logger.debug(s"${getClass.getSimpleName}($key)")
  }

  /**
    * Rather than binding optional defaults inside StreamModule.configure, thus requiring it to know about all the
    * optionals that are out there, we have this OptionalInjectId class that... read more below.
    */
  abstract class OptionalInjectId[T :Manifest] extends InjectId[T] {

    /**
      * I tried to implement this in a way where each instance of this class would register itself via a singleton
      * StreamModule.registerDefault, but since Scala objects are lazily instantiated that doesn't work.  Supposedly
      * the preferred way to provide optional values with Guice is via OptionalBinders, but then some module somewhere
      * still needs to perform the `bind` during the module's `configure` thus requiring StreamModule to know about
      * all of the possible optionals.  Just-in-time (JIT) binding using @Inject, however, allows the binding to occur
      * at injection-time rather than at configure-time.  So that is why we're using this older, un-preferred "holder"
      * pattern as described here:
      *   https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
      *
      * This also means that derived classes of this `OptionalInjectId` class will have to be straight-up classes,
      * not objects, as they'll need to have JIT constructors that can be executed at injection-time.
      *
      * This `value` member cannot be annotated here because `name` is not (yet) `final` which occurs inside the
      * derived classes.  So derived classes will have to provide an overriding implementation of `value` with
      * the @Inject and (optional) @Named annotations as shown here commented out.
      */
    //@Inject(optional = true) @Named(name) // error: "annotation argument needs to be a constant"
    def value: T //= default
  }

  // `final val`s are required so that their values are constants that can be used at compile time in @Named annotations
  object CallingUserId extends InjectId[UUID] { final val name = "calling.user.id" }
  object Query extends InjectId[String] { final val name = "query" }
  object ClockBegin extends InjectId[TimeStamp] { final val name = "clock.begin" }
  object ClockInterval extends InjectId[DurationMils] { final val name = "clock.interval" }

  // using Option[TimeStamp/Long] here works to construct the binding, but it doesn't work when it comes time for
  // injection because Scala's Long gets changed into a Java primitive `long` and then into an Option[Object] in
  // resulting bytecode, so ClockEnd.typ ends up being an Option[Object] that can't be found at injection time
  // more here: https://github.com/codingwell/scala-guice/issues/56
  // Error message: "No implementation for scala.Option<java.lang.Object> annotated with @com.google.inject.name
  // .Named(value=clock.end) was bound."
  object ClockEnd extends InjectId[/*Option[java.lang.Long]*/TimeStamp] { final val name = "clock.end" }

  // optionals
  object LogLevelOptional extends NamelessInjectId[Option[ch.qos.logback.classic.Level]]
  object Query2VecsOptional extends InjectId[Option[Query2VecsType]] { final val name = "query2Vecs" }

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

  /** We need to return a Future.successful(Seq.empty[T]) in a few different places if mbQuerySeq is None. */
  implicit class ExtendedQuerySeq(private val mbQuerySeq: Option[Seq[String]]) extends AnyVal {
    def mapOrEmptyFuture[T](f: String => Future[T])
                           (implicit ec: ExecutionContext): Future[Seq[T]] =
      mbQuerySeq.fold(Future.successful(Seq.empty[T])) { querySeq => Future.sequence(querySeq.map(w => f(w))) }
  }
}