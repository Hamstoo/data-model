package com.hamstoo.services

import java.net.URL
import java.nio.file.{Files, Path, Paths}
import javax.activation.MimeType

import com.hamstoo.models.Page
import com.hamstoo.services.TikaInstance.createCommonParameters
import com.hamstoo.utils.{MediaTypeSupport, TestHelper}
import org.apache.tika.io.IOUtils
import org.apache.tika.metadata.{PDF, TikaCoreProperties}
import org.apache.tika.parser.Parser

/**
  * TikaInstanceTests
  */
class TikaInstanceTests extends TestHelper {

  val pwd: String = System.getProperty("user.dir")
  val currentFile: Path = Paths.get(pwd + "/src/test/scala/com/hamstoo/services/" + getClass.getSimpleName + ".scala")
  val content: Array[Byte] = Files.readAllBytes(currentFile)

  val urlPDF = "http://www.softwareresearch.net/fileadmin/src/docs/teaching/SS13/ST/ActorsInScala.pdf"

  val urlDOC = "http://download.microsoft.com/download/f/7/3/f7395ee6-5642-4ab9-a881-786d0350e88d/skills_development_white_paper_2009.doc"

  "TikaInstance" should "detect MIME types" in {
    TikaInstance.detect(content) shouldEqual "text/plain"
  }

  it should "detect MIME types via Page.apply" in {
    val page = Page(content)
    page.mimeType shouldEqual "text/plain"
  }

  it should "getTitle from parameters set 1" in {
    val is = new URL(urlPDF).openStream()
    val byteArray = Option(IOUtils.toByteArray(is))
    val title = TikaInstance.getTitle(optArrayBytes = byteArray).getOrElse("")
   title shouldEqual "Actors in Scala"
  }

  it should "getTitle from parameters set 2" in {
    val is = new URL(urlDOC).openStream()

    val mimeType =  new MimeType(TikaInstance.detect(content))
    val (parser, contentHandler, metadata, parseContext) = createCommonParameters
    val (titleKey, keywordsKey) = if (MediaTypeSupport.isPDF(mimeType)) {

      (PDF.DOC_INFO_TITLE, PDF.DOC_INFO_KEY_WORDS)
    } else {
      parseContext.set(classOf[Parser], parser)
      (TikaCoreProperties.TITLE, TikaCoreProperties.KEYWORDS)
    }

    parser.parse(is, contentHandler, metadata, parseContext)
    val title = TikaInstance.getTitle(Option(titleKey), Option(metadata)).getOrElse("")
    title  shouldEqual "- What is Nqual"
  }
}
