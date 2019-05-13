/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.config

import com.google.inject.name.Names
import com.hamstoo.services.IDFModel
import com.hamstoo.stream.InjectId
import com.typesafe.config.{Config, ConfigValueFactory}

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
case class ConfigModule(config: Config, autoConfig: Boolean = true) extends BaseModule {

  /**
    * "To create bindings, extend AbstractModule and override its configure method.  In the method body, call
    * `bind` to specify each binding."
    */
  override def configure(): Unit = {
    logger.debug(s"Configuring module: ${classOf[ConfigModule].getName}")
    if (autoConfig)
      bindConfigParams[String]("idfs.resource", "vectors.link", "mongodb.uri")
    InjectId[Config]("config") := config // <- must be @Named b/c hamstoo/GeneralModule also binds a Config instance
    IDFModel.ResourcePathOptional ?= None
  }

  /**
    * Bind configuration parameter values.
    * See: https://github.com/google/guice/wiki/FrequentlyAskedQuestions
    * "To enable [multiple bindings for the same type], bindings support an optional binding annotation"
    * "The annotation and type together uniquely identify a binding."
    * TODO: is there anything similar to `Names.bindProperties()` that would just bind all of these?
    * TODO:   once `Conf` is moved from repr-engine to data-model we can use that class
    */
  def bindConfigParams[T :Manifest](params: String*)
                                            (implicit cast: AnyRef => T = (a: AnyRef) => a.asInstanceOf[T]): Unit = {
    params.foreach { key =>
      //if (config.hasPath(key))
        bind[T].annotatedWith(Names.named(key)).toInstance(cast(config.getAnyRef(key)))
        //bindConstant().annotatedWith(Names.named(key)).to(config.get[String](key))
    }
  }
}

object ConfigValue {
  def apply[T](v: T): com.typesafe.config.ConfigValue = ConfigValueFactory.fromAnyRef(v)
}
