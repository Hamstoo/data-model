package com.hamstoo.utils

import com.hamstoo.test.FlatSpecWithMatchers

/**
  * MediaTypeTests
  */
class MediaTypeTests extends FlatSpecWithMatchers {

  "MediaType" should "(UNIT) be equals'able" in {
    MediaType("text/plain") == MediaType("text/plain") shouldBe true
  }
}
