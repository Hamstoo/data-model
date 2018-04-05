/*
 * Copyright (C) 2017-2018 Hamstoo Inc. <https://www.hamstoo.com>
 */
package com.hamstoo

import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import ch.qos.logback.classic.Level


package object stream {

  // old verbosity levels
  //val VERBOSE_OFF = 0     // Level.INFO
  //val DISPLAY_SCORES = 1  // Level.DEBUG (for displaying scores on the website)
  //val LOG_DEBUG = 2       // Level.TRACE (for performing lots of extra logging in the logs)

  /**
    * Instructions on how to inject optional parameters with Guice.
    * https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
    */

  class LogLevelOptional {
    @Inject(optional = true) @Named("logLevel")
    val value: Level = Level.INFO
  }

  class Query2VecsOptional {
    @Inject(optional = true) @Named("query2Vecs")
    val value: Option[Query2VecsType] = None
  }

  class SearchLabelsOptional {
    @Inject(optional = true) @Named("labels")
    val value: Set[String] = Set.empty[String]
  }
}
