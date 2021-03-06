package com.hamstoo.services

import java.nio.file.{Files, Path, Paths}

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.Page
import com.hamstoo.test.FlatSpecWithMatchers

/**
  * TikaInstanceTests
  */
class TikaInstanceTests extends FlatSpecWithMatchers {

  val pwd: String = System.getProperty("user.dir")
  val currentFile: Path = Paths.get(pwd + "/src/test/scala/com/hamstoo/services/" + getClass.getSimpleName + ".scala")
  val content: Array[Byte] = Files.readAllBytes(currentFile)

  "TikaInstance" should "(UNIT) detect MIME types" in {
    TikaInstance.detect(content) shouldEqual "text/plain"
  }

  it should "(UNIT) detect MIME types via Page.apply" in {
    import com.hamstoo.utils.DataInfo._
    val page = Page(constructMarkId(), ReprType.PUBLIC, content)
    page.mimeType shouldEqual "text/plain"
  }
}
