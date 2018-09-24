/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.google.inject.Injector
import com.hamstoo.stream.injectorly
import com.hamstoo.test.FlatSpecWithMatchers

/**
  * IDFModel tests.
  */
class IDFModelTests extends FlatSpecWithMatchers {

  import com.hamstoo.utils.DataInfo._

  // instantiate components from the Guice injector
  lazy implicit val injector: Injector = appInjector
  lazy val idfModel: IDFModel = injectorly[IDFModel]

  val deviation = 1e-4

  "IDFModel" should "(UNIT) transform" in {
    idfModel.transform("of") shouldEqual 0.005 +- deviation
    idfModel.transform("the") shouldEqual 0.0056 +- deviation
    idfModel.transform("anarchy") shouldEqual 6.3723 +- deviation
    idfModel.transform("likelihood") shouldEqual 6.1745 +- deviation
    idfModel.transform("likable") shouldEqual 9.3272 +- deviation // issue #370
    idfModel.transform("transrapid") shouldEqual 9.3272 +- deviation
    idfModel.transform("fawevzsdirweckivb") shouldEqual 9.3272 +- deviation
  }
}

