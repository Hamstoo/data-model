package com.hamstoo.services

import java.nio.file.{Files, Path, Paths}

import com.hamstoo.models.Page
import com.hamstoo.utils.TestHelper

/**
  * TikaInstanceTests
  */
class TikaInstanceTests extends TestHelper {

  val pwd: String = System.getProperty("user.dir")
  val currentFile: Path = Paths.get(pwd + "/src/test/scala/com/hamstoo/services/" + getClass.getSimpleName + ".scala")
  val content: Array[Byte] = Files.readAllBytes(currentFile)

  "TikaInstance" should "detect MIME types" in {
    TikaInstance.detect(content) shouldEqual "text/plain"
  }

  it should "detect MIME types via Page.apply" in {
    val page = Page(content)
    page.mimeType shouldEqual "text/plain"
  }
}
