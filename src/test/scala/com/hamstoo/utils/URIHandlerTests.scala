package com.hamstoo.utils

import com.hamstoo.test.FlatSpecWithMatchers

class URIHandlerTests extends FlatSpecWithMatchers {

  "URI handler" should "(UNIT) correctly detect XSS content" in {
    val url0 = "http://bobssite.org?q=<script%20type='text/javascript'>alert('xss');</script>"
    URIHandler.isDangerous(url0) shouldBe true

    val url1 = "https://github.com/Hamstoo/hamstoo/issues/208"
    URIHandler.isDangerous(url1) shouldBe false

    val url2 = "hello <a href=\"www.google.com\">*you*</a>"
    URIHandler.isDangerous(url2) shouldBe true
  }
}
