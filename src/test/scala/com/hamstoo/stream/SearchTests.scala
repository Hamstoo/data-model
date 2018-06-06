/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import com.hamstoo.stream.facet.SearchResults
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import play.api.Logger

/**
  * SearchTests
  */
class SearchTests
  extends AkkaMongoEnvironment("SearchTests-ActorSystem")
    with org.scalatest.mockito.MockitoSugar
    with FutureHandler {

  val logger = Logger(classOf[SearchTests])

  val loremIpsum: String =
    """Lorem ipsum is simply dummy text of the printing and typesetting industry. Lorem Ipsum has been the industry's
      |standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to
      |make a type specimen book. It has survived not only five centuries, but also the leap into electronic
      |typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset
      |sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker
      |including versions of Lorem Ipsum.""".stripMargin

  "Previewer" should "preview" in {
    val previewer = SearchResults.Previewer("\"has survived\" lorem ipsum",
                                            Seq("has", "survived", "lorem", "ipsum").map((_, 1)), "")
    val preview = previewer.apply(1.0, loremIpsum)

    preview._2.head._1 shouldEqual 0.1351 +- 0.0001
    preview._2(1)._1 shouldEqual 0.0958 +- 0.0001
    preview._2(2)._1 shouldEqual 0.0509 +- 0.0001

    preview._2.head._2 shouldEqual """...top publishing software like Aldus PageMaker
                                     |including versions of <b>Lorem</b> <b>Ipsum</b>.""".stripMargin
    preview._2(1)._2 shouldEqual """<b>Lorem</b> <b>ipsum</b> is simply dummy text of the printing and typesetting industry....""".stripMargin
    preview._2(2)._2 shouldEqual """...own printer took a galley of type and scrambled it to
                                   |make a type specimen book. It <b>has survived</b> not only five centuries, but also the leap into elect...""".stripMargin
  }

  it should "not return previews from a single word more than once" in {
    val previewer = SearchResults.Previewer("centuries", Seq("centuries").map((_, 1)), "")
    val preview = previewer.apply(1.0, loremIpsum)

    preview._2.head._1 shouldEqual 0.0190 +- 0.0001
    preview._2.head._2 shouldEqual """...ype and scrambled it to
                                     |make a type specimen book. It has survived not only five <b>centuries</b>, but also the leap into electronic
                                     |typesetting, remaining e...""".stripMargin
  }
}