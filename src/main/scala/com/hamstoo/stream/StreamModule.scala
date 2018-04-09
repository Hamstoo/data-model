/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.Materializer
import com.google.inject.name.{Named, Names}
import com.google.inject.{AbstractModule, Injector, Provides, Singleton}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import net.codingwell.scalaguice.ScalaModule
import play.api.Logger

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  *
  * This StreamModule trait requires an implementation of `configure`.
  */
trait StreamModule extends AbstractModule with ScalaModule {

  val logger = Logger(classOf[StreamModule])

  // having an `implicit this` enables the `:=` methods of InjectId and this class, StreamModule
  implicit val implicitThis: StreamModule = this

  /** An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal? */
  implicit class InjectVal[T :ClassTag :TypeTag](private val _typ: Class[T]) /*extends AnyVal*/ {
    def :=(instance: T)(implicit module: StreamModule): Unit = module.assign(new NamelessInjectId[T], instance)
  }

  /** Bind a (possibly named) instance given its (type, name) pair, which Guice uses to uniquely identify bindings. */
  def assign[T :ClassTag :TypeTag](injectId: NamelessInjectId[T], instance: T): Unit = injectId match {
    case iid: InjectId[_] => bind[iid.typ].annotatedWith(Names.named(iid.name)).toInstance(instance)
    case iid /*Nameless*/ => bind[iid.typ]                                     .toInstance(instance)
  }

  /** See Query2VecsOptional.  There are 2 providers of objects named "query2Vecs" but they return different types. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2VecsOptional(@Named(Query.name) query: Query.typ, vecSvc: VectorEmbeddingsService)
                               (implicit ec: ExecutionContext): Query2VecsOptional.typ =
    Some(vecSvc.query2Vecs(query))

  /** One of the providers is needed for when "query2Vecs" is optional and the other, this one, for when it isn't. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2Vecs(mbQuery2Vecs: Query2VecsOptional): Query2VecsType =
    mbQuery2Vecs.value.get

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
