/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.config

import com.google.inject._
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.stream._
import play.api.Logger

import scala.concurrent.ExecutionContext

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  *
  * This StreamModule trait requires an implementation of `configure`.
  */
class StreamModule extends BaseModule {

  import StreamModule._

  /** Configure optional default values. */
  override def configure(): Unit = {
    super.configure()
    logger.debug(s"Configuring module: ${classOf[StreamModule].getName}")

    // see, using this method of declaring optional defaults requires StreamModule to know about them
    LogLevelOptional ?= None
    Query2VecsOptional ?= None
  }

  /** See Query2VecsOptional.  There are 2 providers of objects named "query2Vecs" but they return different types. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2VecsOptional(@Named(Query.name) query: Query.typ, vecSvc: VectorEmbeddingsService)
                               (implicit ec: ExecutionContext): Query2VecsOptional.typ =
    Some(vecSvc.query2Vecs(query))

  /** One of the providers is needed for when "query2Vecs" is optional and the other, this one, for when it isn't. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2Vecs(@Named(Query2VecsOptional.name) mbQuery2Vecs: Query2VecsOptional.typ): Query2VecsType =
    mbQuery2Vecs.get

  /**
    * StreamModules are typically passed to `parentInjector.createChildInjector(new StreamModule)`.  In such cases
    * optional bindings (e.g. see OptionalInjectId) can be constructed by the parentInjector because the optional
    * defaults are available to the parent.  But if we override an optional's default in the child module, then we
    * want to ignore the parent's bindings.  So we need a way to inject the child injector into any OptionalInjectIds
    * that we encounter.  We can't inject a raw Injector into OptionalInjectIds, but we can inject a wrapped Injector
    * using some sort of wrapper that the parent won't be able to JIT bind (e.g. won't have a provider for).  We
    * use an Option as our wrapper for this purpose exactly.
    *
    * See also:
    *   https://github.com/google/guice/issues/847
    *   https://groups.google.com/forum/#!msg/google-guice/Q592mWKTS1Q/9V_fsy3QeFYJ
    *
    * @param injector  This injector (assuming our parent injector is unable to JIT bind an Option[Injector]).
    */
  @Provides @Singleton
  def provideStreamInjector(injector: Injector): WrappedInjector = {
    logger.debug(s"Stream injector: ${injector.hashCode}")
    Some(injector)
  }
}

object StreamModule {
  val logger = Logger(classOf[StreamModule])
  type WrappedInjector = Option[Injector]
}
