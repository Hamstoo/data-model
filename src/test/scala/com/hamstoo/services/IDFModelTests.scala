package com.hamstoo.services

import com.hamstoo.test.FlatSpecWithMatchers

/**
  * IDFModel tests.
  */
class IDFModelTests extends FlatSpecWithMatchers {

  import com.hamstoo.utils.DataInfo._
  lazy val idfModel = new IDFModel(idfsResource)
  val deviation = 1e-4

  "IDFModel" should "(UNIT) transform" in {
    idfModel.transform("of") shouldEqual 0.005 +- deviation
    idfModel.transform("the") shouldEqual 0.0056 +- deviation
    idfModel.transform("anarchy") shouldEqual 6.3723 +- deviation
    idfModel.transform("transrapid") shouldEqual 9.3272 +- deviation
    idfModel.transform("fawevzsdirweckivb") shouldEqual 9.3272 +- deviation
  }
}

