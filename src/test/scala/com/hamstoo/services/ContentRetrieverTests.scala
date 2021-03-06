/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.hamstoo.models.Page
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaEnvironment
import com.hamstoo.utils.{MediaType, ObjectId}
import play.api.libs.ws.ahc.AhcWSClient
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

/**
  * ContentRetriever tests.  These tests were implemented to address the implementation of ContentRetriever
  * that existed as of repr-engine commit `c7bede9` (2017-08-16).
  */
class ContentRetrieverTests
  extends AkkaEnvironment("ContentRetrieverTests-ActorSystem")
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  val contriever = new ContentRetriever(httpClient = AhcWSClient())
  val id: ObjectId = constructMarkId()
  val reprType: ReprType.Value = ReprType.PUBLIC

  "ContentRetriever" should "(UNIT) fail on bogus URL" in {
    val bogusURL = "http://string"
    intercept[Exception] { contriever.retrieve(reprType, bogusURL).futureValue }
  }

  it should "(UNIT) succeed on non-bogus URL and be able to get its title" in {
    val urlHTML = "http://docs.scala-lang.org/overviews/core/futures.html"
    val page = contriever.retrieve(reprType, urlHTML).futureValue
    page shouldBe a [Page]
    ContentRetriever.getTitle(page) shouldBe Some("Futures and Promises | Scala Documentation")
  }

  it should "(UNIT) be able to get another title" in {
    val url = "https://brohrer.github.io/how_optimization_works_1.html"
    //val page = contriever.retrieve(reprType, url).futureValue

    val html = """<html>
                 |  <script type="text/javascript">var blog_title = "How optimization for machine learning works, Part 1";</script>
                 |  <head>
                 |    <script type="text/javascript" src="javascripts/blog_head.js"></script>
                 |  </head>
                 |</html>
                 |"""
    val page = Page("bogusMarkId", reprType, html.toCharArray.map(_.toByte))

    page shouldBe a [Page]
    ContentRetriever.getTitle(page) shouldBe Some("How optimization for machine learning works, Part 1")
  }

  it should "(UNIT) get PDF titles" in {
    val urlPDF = "http://www.softwareresearch.net/fileadmin/src/docs/teaching/SS13/ST/ActorsInScala.pdf"
    val page = contriever.retrieve(reprType, urlPDF).futureValue
    ContentRetriever.getTitle(page) shouldBe Some("Actors in Scala")
  }

  // temporarily (?) disabling this test; perhaps we've been blacklisted (?)
  it should "(UNIT) not duplicate frames which are nested in framesets and " +
            "load frames which are not nested in framesets" in {

    val htmlWithFrames = """<html>
    <head>
      <meta http-equiv="Content-Language" content="en-us">
        <title>Apache Ant&trade; User Manual</title>
        </head>
        <frameset cols="26%,74%">
          <frame src="toc.html" name="navFrame">
          <frame src="cover.html" name="mainFrame">
          <frame src="cover.html" name="mainFrame">
        </frameset>
    </html>"""

    val elems = contriever.loadFrames("https://ant.apache.org/manual/",
                      new Page(id, reprType, MediaType.TEXT_HTML.toString, htmlWithFrames.toCharArray.map(_.toByte))).futureValue
    // should load only 3 frames total
    elems._2 shouldBe 3
  }

  /*it should "(UNIT) return redirected URL when following redirects, even for 301s" in {
    val url = "http://www.scala-blogs.org/2008/01/maven-for-scala.html?showComment=1199730180000"
    val (red, _) = contriever.getTitle(url).futureValue
    red shouldBe "https://bvokpharm.net/erectile-dysfunction/viagra.html"
  }*/
}
