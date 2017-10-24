package com.hamstoo.services

import akka.stream.ActorMaterializer
import com.hamstoo.models.Page
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaEnvironment
import play.api.libs.ws.ahc.AhcWSClient

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * ContentRetriever tests.  These tests were implemented to address the implementation of ContentRetriever
  * that existed as of repr-engine commit `c7bede9` (2017-08-16).
  */
class ContentRetrieverTests
  extends AkkaEnvironment("ContentRetrieverTests-ActorSystem")
    with FutureHandler {

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val contriever = new ContentRetriever(httpClient = AhcWSClient())

  "ContentRetriever" should "(UNIT) fail on bogus URL" in {
    val bogusURL = "http://string"
    intercept[Exception] { contriever.retrieve(bogusURL).futureValue }
  }

  it should "(UNIT) succeed on non-bogus URL" in {
    val url = "http://hamstoo.com"
    contriever.retrieve(url).futureValue shouldBe a [Page]
  }
}
