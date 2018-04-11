/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject._
import com.google.inject.multibindings.OptionalBinder
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import net.codingwell.scalaguice.ScalaModule
import play.api.Logger

import scala.concurrent.ExecutionContext

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  *
  * This StreamModule trait requires an implementation of `configure`.
  */
abstract class StreamModule extends AbstractModule with ScalaModule {

  val logger = Logger(classOf[StreamModule])

  // having an `implicit this` enables the `:=` methods of InjectId and this class, StreamModule
  implicit val implicitThis: StreamModule = this

  /** Configure optional default values. */
  override def configure(): Unit = {
    super.configure()
    logger.info(s"Configuring module: ${classOf[StreamModule].getName}")

    LogLevelOptional ?= None
    Query2VecsOptional ?= None
    SearchLabelsOptional ?= Set.empty[String]
    SearchUserIdOptional ?= None
  }

  /**
    * An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal?
    * Example: `classOf[ExecutionContext] := system.dispatcher`
    */
  implicit class InjectVal[T :Manifest](private val _typ: Class[T]) /*extends AnyVal*/ {
    def :=(instance: T): Unit = new NamelessInjectId[T] := instance
    def ?=(default: T): Unit = new NamelessInjectId[T] ?= default
  }

  /**
    * Bind a (possibly named) instance given its (type, name) pair, which Guice uses to uniquely identify bindings.
    *
    * Using `OptionalBinder.setBinding` here instead of simply calling `bind` because the former works when
    * `OptionalBinder.setDefault` has been called previously while the latter does not.
    */
  def assign[T :Manifest](key: Key[T], instance: T): Unit = //bind(key).toInstance(instance)
    OptionalBinder.newOptionalBinder(binder(), key).setBinding().toInstance(instance)

  /** Bind an optional injectable argument with a default value. */
  def assignOptional[T :Manifest](key: Key[T], default: T): Unit =
    OptionalBinder.newOptionalBinder(binder(), key).setDefault().toInstance(default)

  /** See Query2VecsOptional.  There are 2 providers of objects named "query2Vecs" but they return different types. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2VecsOptional(@Named(Query.name) query: Query.typ, vecSvc: VectorEmbeddingsService)
                               (implicit ec: ExecutionContext): Query2VecsOptional.typ =
    Some(vecSvc.query2Vecs(query))

  /** One of the providers is needed for when "query2Vecs" is optional and the other, this one, for when it isn't. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2Vecs(@Named(Query2VecsOptional.name) mbQuery2Vecs: Query2VecsOptional.typ): Query2VecsType =
    mbQuery2Vecs.get

  @Provides @Singleton
  def buildModel(injector: Injector, clock: Clock, materializer: Materializer): FacetsModel =
                                                              new FacetsModel(injector)(clock, materializer) {

    //import net.codingwell.scalaguice.InjectorExtensions._
    //val qc: QueryCorrelation = injector.instance[QueryCorrelation]

    add[SearchResults]() // "semanticRelevance"

    // * so a stream can only be reused (singleton) if its defined inside a type
    //   * but eventually we can make this work to automatically generate new types (perhaps)
    //   * add[classOf[QueryCorrelation] + 2]()
    //   * https://www.google.com/search?q=dynamically+create+type+scala&oq=dynamically+create+type+scala&aqs=chrome..69i57.5239j1j4&sourceid=chrome&ie=UTF-8
    // * the (source) clock won't know what time its starting with until the data streams have
    //   all been wired together (via the Injector)

    //add(AvailablityBias / Recency) -- see How to Think screenshot
    //add(ConfirmationBias)
    //add(TimeSpent)
    //add(Rating)
    //add(SearchRelevance)
  }
}
