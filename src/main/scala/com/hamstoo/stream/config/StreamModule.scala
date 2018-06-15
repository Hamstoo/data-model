/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.config

import com.google.inject._
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.stream._

import scala.concurrent.ExecutionContext

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
case class StreamModule() extends BaseModule {

  import StreamModule._

  /** See Query2VecsOptional.  There are 2 providers of objects named "query2Vecs" but they return different types. */
  @Provides @Singleton @Named(Query2Vecs.name)
  def provideQuery2VecsOpt(query: QueryOptional, vecSvc: VectorEmbeddingsService)
                        (implicit ec: ExecutionContext): Query2Vecs.typ =
    if (query.value.isEmpty) None else Some(vecSvc.query2Vecs(query.value))

  /** provideQuery2VecsOpt is needed for when query2Vecs is optional and this one for when not, as by SearchResults. */
  @Provides @Singleton @Named(Query2Vecs.name)
  def provideQuery2Vecs(@Named(Query2Vecs.name) mbQuery2Vecs: Query2Vecs.typ): Query2VecsType = mbQuery2Vecs.get

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
  type WrappedInjector = Option[Injector]
}
