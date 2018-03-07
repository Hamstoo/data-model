package com.hamstoo.stream

import java.util.UUID

import com.google.inject.name.Names
import com.google.inject.name.Named
import com.google.inject.{Injector, Provides}
import com.hamstoo.models.Representation.{Vec, VecFunctions}
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.utils.ConfigModule
import com.typesafe.config.Config

import scala.concurrent.{ExecutionContext, Future}

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class StreamModule(config: Config) extends ConfigModule(config) {

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

    //bind[ControllerComponents].to[DefaultControllerComponents]
    //bind[Clock].to[DefaultClock]
    //bind[Sources].to[DefaultSources] ...or... bind[Model].to[DefaultModel] ...or... could this be JIT/implicit too?
            // define all the different variable sources in default sources and
            // then get them from Injector(ModelBuilder).get(classOf[Model])
            // then, once constructed, bind Model to a consumer sink
  }

  /** A word vector for the query string, for computing stats in reference to. */
  @Provides @Named("query.vec")
  def provideQueryVec(@Named("query") query: String, vecSvc: VectorEmbeddingsService)
                     (implicit ec: ExecutionContext): Future[Vec] = for {
    wordMasses: Seq[VectorEmbeddingsService.WordMass] <- vecSvc.query2Vecs(query)._2
  } yield {
    wordMasses.foldLeft(Vec.empty) { case (agg, e) =>
      if (agg.isEmpty) e.scaledVec else agg + e.scaledVec
    }.l2Normalize
  }

  @Provides
  def buildModel(injector: Injector): StreamModel = new StreamModel(injector) {

    //import net.codingwell.scalaguice.InjectorExtensions._
    //val qc: QueryCorrelation = injector.instance[QueryCorrelation]

    add[QueryCorrelation]() // "semanticRelevance"

    //add[QuerySearchScore]() // "syntacticRelevance"

    //add(AvailablityBias)
    //add(ConfirmationBias)
  }

}
