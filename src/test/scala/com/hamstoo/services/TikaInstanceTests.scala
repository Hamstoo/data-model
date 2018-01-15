package com.hamstoo.services

import java.nio.file.{Files, Path, Paths}

import com.hamstoo.models.{Page, Representation}
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
    val uuid = constructUserId()
    val id = constructMarkId()

    val page = Page(uuid, id, Representation.PUBLIC, content)
    page.mimeType shouldEqual "text/plain"
  }
}
