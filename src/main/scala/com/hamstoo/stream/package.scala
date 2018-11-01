/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.net.URLDecoder
import java.util.UUID

import akka.stream.Attributes
import com.google.inject.{ConfigurationException, Inject, Injector, Key}
import com.google.inject.name.Names
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.stream.config.{BaseModule, StreamModule}
import net.codingwell.scalaguice.typeLiteral
import net.codingwell.scalaguice.InjectorExtensions._
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


package object stream {

  /**
    * Similar to `implicitly` where the "nether world" in this case is an implicit Injector's namespace.
    *
    * Sote that scalaguice (i.e. `injector.instance`) still uses old Scala version implicit Manifests, presumably
    * for backwards compatibility.
    */
  def injectorly[A :Manifest](implicit injector: Injector): A = injector.instance[A]

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
    * For example, to use one of these things for binding inside a Module.configure method:
    *   InjectId[Double]("name_key") := 2.5
    *   InjectId[String]("name_key") := "some_value"
    *
    * See also: `static <T> Key<T> get(Class<T> type, AnnotationStrategy annotationStrategy)` in Key.java
    *   In other words, Guice already has this concept, but the name value can't be made `final`/constant.
    */
  abstract class /*RichKey*/InjectId[T :Manifest] extends NamelessInjectId[T] {
    def name: String
    override val key: Key[typ] = Key.get(typeLiteral[typ], Names.named(name))
    if (key != null) Logger.debug(s"${getClass.getSimpleName}($key)")
  }

  /** If a `final val name` isn't required for a @Named annotation, then this factory can be used. */
  object InjectId {
    // (1) defining `name` as a `val` here causes a NPE
    // (2) using `name` instead of `_name` as apply's argument hangs (https://hamstoo.com/my-marks/11SuyL1ZqgXzJ5ik)
    def apply[T :Manifest](_name: String): InjectId[T] = new InjectId[T] { override def name: String = _name }
  }

  /**
    * Rather than binding optional defaults inside StreamModule.configure, thus requiring it to know about all the
    * optionals that are out there, we have this OptionalInjectId class that allows the default values to be
    * defined right along with the keys themselves.
    *
    * I tried to implement this in a way where each instance of this class would register itself via a singleton
    * StreamModule.registerDefault, but since Scala objects are lazily instantiated that doesn't work.  Supposedly
    * the preferred way to provide optional values with Guice is via OptionalBinders, but then some module somewhere
    * still needs to perform the `bind` during the module's `configure` thus requiring StreamModule to know about
    * all of the possible optionals.  So rather than pushing instances of this class to StreamModule, instead we
    * access the StreamModule injector inside this class via its StreamModule.WrappedInjector member.
    *
    * Note this class only works with StreamModule, not ConfigModule, because the former has the @Provides method
    * while the latter does not.  This is by design; if ConfigModule had the same @Provides method we'd be back
    * to square one.  This is only really relevant for IDFModel.
    */
  class OptionalInjectId[T :Manifest](_name: String, default: => T = null) extends InjectId[T] {

    // hamstoo.ProcessedSearchString lowercase'izes all query args so we need to here also
    // TODO: is this dumb? or should it be done for InjectId also?
    override def name: String = _name.toLowerCase

    /**
      * So when Guice constructs an OptionalInjectId, it will call this `injector_` mutator, but we don't have to when
      * we construct one.  This is called setter injection:
      *     https://groups.google.com/forum/#!topic/google-guice/KFKP6Zd6vu0
      *
      * For why this is an Option[Injector] and not just a raw Injector (besides the fact that it's initialized to
      * None), see the ScalaDoc for `StreamModule.provideStreamInjector`.
      */
    private var injector: StreamModule.WrappedInjector = None
    @Inject def injector_(inj: StreamModule.WrappedInjector): Unit = injector = inj

    /** Use the injector to construct a (possibly annotated) T. */
    def value: T = {
      if (injector.isEmpty)
        throw new NullPointerException(s"Unable to get injected value for $key because no injector is available; OptionalInjectId values can only be gotten at (dependency) injection time")
      Logger.debug(s"Getting instance for $key (default = $default) from injector ${injector.get.hashCode}")
      Try(injector.get.getInstance(key)).recover {
        case e: ConfigurationException if e.getMessage.contains("No implementation for") =>
          Logger.debug(s"Using default instance $default given exception: $e")
          default
        case e => Logger.error("Unexpected exception type", e)
          throw e
      }.get
    }
  }

  // `final val`s are required so that their values are constants that can be used at compile time in @Named annotations
  object CallingUserId extends InjectId[Option[UUID]] { final val name = "calling.user.id" }

  // optional bindings (default values specified in StreamModule.configure)
  case class LogLevelOptional() extends OptionalInjectId[Option[ch.qos.logback.classic.Level]]("", None)
  case class QueryOptional() extends OptionalInjectId[String]("query", "")

  // formerly an optional binding, still an Option, but the binding optionality is now implemented via QueryOptional
  object Query2Vecs extends InjectId[Option[Query2VecsType]] { final val name = "query2Vecs" }

  /** One might think that getting the name of a stream would be easier than this. */
  def streamName[S](stream: S): String = {

    import scala.language.reflectiveCalls

    // TraveralBuilder is a private[akka] type so we need to use a generic type parameter T here instead
    type Duck[T] = { val traversalBuilder: T /*akka.stream.impl.TraversalBuilder*/ }

    // it's unclear whether or not this might throw an exception in some cases which is why it's wrapped in a Try
    // more here: https://stackoverflow.com/questions/1988181/pattern-matching-structural-types-in-scala
    val x: Option[Attributes.Name] = Try {
      (stream: @unchecked)  match {
        //case simp: Source[Data[A0], Mat] => simp.traversalBuilder.attributes.get[Attributes.Name]
        //case fimp: Flow[In, Data[A0], Mat] => fimp.traversalBuilder.attributes.get[Attributes.Name]
        case duck: Duck[{ def attributes: Attributes }] @unchecked =>
          duck.traversalBuilder.attributes.get[Attributes.Name]
        case _ => None // make it a total function to avoid MatchErrors
      }
    }.getOrElse(None)

    x.fold("<noname>") { attr => URLDecoder.decode(attr.n, "UTF-8") }
  }

  /** We need to return a Future.successful(Seq.empty[T]) in a few different places if mbQuerySeq is None. */
  implicit class ExtendedQuerySeq(private val mbQuerySeq: Option[Seq[String]]) extends AnyVal {
    def mapOrEmptyFuture[T](f: String => Future[T])
                           (implicit ec: ExecutionContext): Future[Seq[T]] =
      mbQuerySeq.fold(Future.successful(Seq.empty[T])) { querySeq => Future.sequence(querySeq.map(w => f(w))) }
  }
}
