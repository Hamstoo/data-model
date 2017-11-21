package com.hamstoo.services

import com.hamstoo.test.FlatSpecWithMatchers
import com.hamstoo.utils.MediaType

/**
  * MediaTypeTests
  */
class MediaTypeTests extends FlatSpecWithMatchers {

  "MediaType" should "(UNIT) be equals'able" in {
    MediaType("text/plain") == MediaType("text/plain") shouldBe true
  }
}
