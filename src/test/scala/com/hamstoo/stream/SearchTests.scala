/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
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
                                            Seq("has", "survived", "lorem", "ipsum").map((_, 1)))
    val preview = previewer.apply(1.0, loremIpsum)

    val s0 = "<b>Lorem</b> <b>ipsum</b> is simply dummy text of the printing and typesetting industry. <b>Lorem</b> <b>Ipsum</b> <b>has</b> been the indus..."
    val s1 = """...publishing software like Aldus PageMaker
               |including versions of <b>Lorem</b> <b>Ipsum</b>.""".stripMargin
    val s2 = """...ok a galley of type and scrambled it to
               |make a type specimen book. It <b>has survived</b> not only five centuries, but also the leap into electronic
               |typesett...""".stripMargin

    preview._2 shouldEqual Seq(0.08915434439506159 -> s0, 0.08559738886488251 -> s1, 0.08006528105138398 -> s2)
  }

  it should "not return previews from a single word more than once" in {
    val previewer = SearchResults.Previewer("centuries", Seq("centuries").map((_, 1)))
    val preview = previewer.apply(1.0, loremIpsum)

    val s0 = """...rambled it to
               |make a type specimen book. It has survived not only five <b>centuries</b>, but also the leap into electronic
               |typesetting, remaining essentially...""".stripMargin

    preview._2 shouldEqual Seq(0.02996577625672251 -> s0)
  }
}