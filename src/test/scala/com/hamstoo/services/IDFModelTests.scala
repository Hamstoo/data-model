/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.google.inject.{Guice, Injector}
import com.hamstoo.stream.config.ConfigModule
import com.hamstoo.test.FlatSpecWithMatchers
import com.hamstoo.utils.DataInfo

/**
  * IDFModel tests.
  */
class IDFModelTests extends FlatSpecWithMatchers {

  // create a Guice object graph configuration/module and instantiate it to an injector
  lazy val injector: Injector = Guice.createInjector(new ConfigModule(DataInfo.config))

  // instantiate components from the Guice injector
  import net.codingwell.scalaguice.InjectorExtensions._
  lazy val idfModel: IDFModel = injector.instance[IDFModel]

  val deviation = 1e-4

  "IDFModel" should "(UNIT) transform" in {
    idfModel.transform("of") shouldEqual 0.005 +- deviation
    idfModel.transform("the") shouldEqual 0.0056 +- deviation
    idfModel.transform("anarchy") shouldEqual 6.3723 +- deviation
    idfModel.transform("transrapid") shouldEqual 9.3272 +- deviation
    idfModel.transform("fawevzsdirweckivb") shouldEqual 9.3272 +- deviation
  }
}

