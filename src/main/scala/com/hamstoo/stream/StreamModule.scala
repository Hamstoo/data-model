package com.hamstoo.stream

import java.util.UUID

import com.google.inject.name.Names
import com.google.inject.name.Named
import com.google.inject.{AbstractModule, Injector, Provides}
import com.hamstoo.models.Representation.Vec
import com.hamstoo.services.{IDFModel, Vectorizer}
import net.codingwell.scalaguice.ScalaModule
import play.api.Configuration

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class StreamModule(config: Configuration) extends AbstractModule with ScalaModule {

  /**
    * "To create bindings, extend AbstractModule and override its configure method. In the method body, call
    * `bind` to specify each binding."
    */
  override def configure(): Unit = {

    // bind string configuration parameter values
    // see: https://github.com/google/guice/wiki/FrequentlyAskedQuestions
    // "To enable [multiple bindings for the same type], bindings support an optional binding annotation"
    // "The annotation and type together uniquely identify a binding."
    // TODO: is there anything similar to `Names.bindProperties()` that would just bind all of them?
    val params = Seq("idfs.resource", "vectors.link", "query")
    params.foreach { key =>
      //bindConstant().annotatedWith(Names.named(key)).to(config.get[String](key))
      bind[String].annotatedWith(Names.named(key)).toInstance(config.get[String](key))
    }

    // which user are we computing stats/facet values for?
    bind[UUID].annotatedWith(Names.named("user.id")).toInstance(UUID.fromString(config.get[String]("user.id")))

    //bind[ControllerComponents].to[DefaultControllerComponents]
    //bind[Clock].to[DefaultClock]
    //bind[Sources].to[DefaultSources] ...or... bind[Model].to[DefaultModel] ...or... could this be JIT/implicit too?
            // define all the different variable sources in default sources and
            // then get them from Injector(ModelBuilder).get(classOf[Model])
            // then, once constructed, bind Model to a consumer sink
  }

  @Provides @Named("query.vec")
  def provideQueryVec(@Named("query") query: String, idfModel: IDFModel, vectorizer: Vectorizer): Vec = {




    Seq.empty[Double]




  }

  @Provides
  def provideModel(injector: Injector): StreamModel = new StreamModel(injector) {

    //import net.codingwell.scalaguice.InjectorExtensions._
    //val qc: QueryCorrelation = injector.instance[QueryCorrelation]

    add[QueryCorrelation]() // "semanticRelevance"

    //add[QuerySearchScore]() // "syntacticRelevance"

    //add(AvailablityBias)
    //add(ConfirmationBias)
  }

}
