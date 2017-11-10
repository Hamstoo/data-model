package com.hamstoo.services

import akka.stream.ActorMaterializer
import com.hamstoo.models.Page
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaEnvironment
import com.hamstoo.utils.DataInfo
import play.api.libs.ws.ahc.AhcWSClient

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * ContentRetriever tests.  These tests were implemented to address the implementation of ContentRetriever
  * that existed as of repr-engine commit `c7bede9` (2017-08-16).
  */
class ContentRetrieverTests
  extends AkkaEnvironment("ContentRetrieverTests-ActorSystem")
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val contriever = new ContentRetriever(httpClient = AhcWSClient())

  "ContentRetriever" should "(UNIT) fail on bogus URL" in {
    val bogusURL = "http://string"
    intercept[Exception] { contriever.retrieve(bogusURL).futureValue }
  }

  it should "(UNIT) succeed on non-bogus URL and be able to get its title" in {
    val page = contriever.retrieve(urlHTML).futureValue
    page shouldBe a [Page]
    import com.hamstoo.services.ContentRetriever.PageFunctions
    page.getTitle shouldBe Some("Futures and Promises | Scala Documentation")
  }

  it should "(UNIT) get PDF titles" in {
    val page = contriever.retrieve(urlPDF).futureValue
    import com.hamstoo.services.ContentRetriever.PageFunctions
    page.getTitle shouldBe Some("Actors in Scala")
  }
}
