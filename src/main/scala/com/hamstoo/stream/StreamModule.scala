/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

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
class StreamModule extends BaseModule {

  import StreamModule._

  /** Configure optional default values. */
  override def configure(): Unit = {
    super.configure()
    logger.info(s"Configuring module: ${classOf[StreamModule].getName}")

    LogLevelOptional ?= None
    Query2VecsOptional ?= None
    SearchLabelsOptional ?= Set.empty[String]
    SearchUserIdOptional ?= None
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
}

object StreamModule {
  val logger = Logger(classOf[StreamModule])
}
