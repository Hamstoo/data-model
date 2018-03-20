package com.hamstoo.stream

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.name.Names
import com.google.inject.name.Named
import com.google.inject.{Injector, Provides}
import com.hamstoo.models.Representation.{Vec, VecFunctions}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.utils.ConfigModule
import com.typesafe.config.Config
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class StreamModule(config: Config) extends ConfigModule(config) {

  val logger = Logger(classOf[StreamModule])

  /**
    * "To create bindings, extend AbstractModule and override its configure method. In the method body, call
    * `bind` to specify each binding."
    */
  override def configure(): Unit = {

    // bind typical config parameters like `idfs.resource` and `vectors.link`
    super.configure()

    // which query string are we computing stats/facet values for?
    bind[String].annotatedWith(Names.named("query")).toInstance(config.getString("query"))

    // which user are we computing stats/facet values for?
    bind[UUID].annotatedWith(Names.named("user.id")).toInstance(UUID.fromString(config.getString("user.id")))
  }


  @Provides @Named("query.vec")
  def provideQueryVec(@Named("query") query: String, vecSvc: VectorEmbeddingsService)
                     (implicit ec: ExecutionContext): Future[Vec] = for {

    // TODO: how is this getting a real vector during testing?
    wordMasses <- vecSvc.query2Vecs(query)._2
  } yield {
    val qvec = wordMasses.foldLeft(Vec.empty) { case (agg, e) =>
      if (agg.isEmpty) e.scaledVec else agg + e.scaledVec
    }.l2Normalize
    logger.warn(s"Query vector for '$query' (first 10 dimensions): ${qvec.take(10)}")
    qvec
  }

  @Provides
  def buildModel(injector: Injector, clock: Clock, materializer: Materializer): FacetsModel =
                                                              new FacetsModel(injector)(clock, materializer) {

    //import net.codingwell.scalaguice.InjectorExtensions._
    //val qc: QueryCorrelation = injector.instance[QueryCorrelation]

    add[QueryCorrelation]() // "semanticRelevance"

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
