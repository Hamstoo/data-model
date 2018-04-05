/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject.{Injector, Provides, Singleton}
import com.hamstoo.models.Representation.{Vec, VecFunctions}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.utils.ConfigModule
import com.typesafe.config.Config
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class StreamModule(config: Config) extends ConfigModule(config) {

  override val logger = Logger(classOf[StreamModule])

  /**
    * "To create bindings, extend AbstractModule and override its configure method. In the method body, call
    * `bind` to specify each binding."
    */
  override def configure(): Unit = {

    // bind typical config parameters like `idfs.resource` and `vectors.link`
    super.configure()
    logger.info(s"Configuring module: ${classOf[StreamModule].getName}")

    // which query string are we computing stats/facet values for?
    bindConfigParams[String]("query")

    // which user are we computing stats/facet values for?
    implicit val cast = (a: AnyRef) => UUID.fromString(a.asInstanceOf[String])
    bindConfigParams[UUID]("calling.user.id")
  }

  @Provides @Singleton @Named("query2Vecs")
  def provideQuery2Vecs(@Named("query") query: String, vecSvc: VectorEmbeddingsService)
                       (implicit ec: ExecutionContext): Query2VecsType =
    vecSvc.query2Vecs(query)

  @Provides @Singleton @Named("query.vec")
  def provideQueryVec(@Named("query2Vecs") query2Vecs: Query2VecsType)
                     (implicit ec: ExecutionContext): Future[Vec] =
    query2Vecs._2.map { wordMasses =>
      wordMasses.foldLeft(Vec.empty) { case (agg, e) =>
        if (agg.isEmpty) e.scaledVec else agg + e.scaledVec
      }.l2Normalize
    }

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
