package com.hamstoo.utils

import com.google.inject.name.Names
import com.google.inject.AbstractModule
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class ConfigModule(config: Config) extends AbstractModule with ScalaModule {

  /**
    * "To create bindings, extend AbstractModule and override its configure method.  In the method body, call
    * `bind` to specify each binding."
    */
  override def configure(): Unit = {

    // bind string configuration parameter values
    // see: https://github.com/google/guice/wiki/FrequentlyAskedQuestions
    // "To enable [multiple bindings for the same type], bindings support an optional binding annotation"
    // "The annotation and type together uniquely identify a binding."
    // TODO: is there anything similar to `Names.bindProperties()` that would just bind all of these?
    // TODO:   once `Conf` is moved from repr-engine to data-model we can use that class
    val params = Seq("idfs.resource", "vectors.link")
    params.foreach { key =>
      //bindConstant().annotatedWith(Names.named(key)).to(config.get[String](key))
      bind[String].annotatedWith(Names.named(key)).toInstance(config.getString(key))
    }

    val lparams = Seq("clock.begin", "clock.end", "clock.interval")
    lparams.foreach { key => bind[Long].annotatedWith(Names.named(key)).toInstance(config.getLong(key)) }
  }
}
