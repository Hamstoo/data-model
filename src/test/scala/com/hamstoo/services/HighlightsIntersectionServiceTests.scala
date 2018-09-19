/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.hamstoo.models.Highlight
import com.hamstoo.models.Highlight.{Position, PositionElement => PosElem}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import play.api.libs.json.{JsValue, Json}

/**
  * Tests of highlights intersection code.
  */
class HighlightsIntersectionServiceTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler {

  val paths: Seq[String] = for {i <- 0 to 9} yield s"html/body/p$i"

  val texts: Seq[String] = "es an impression in the reader’s mind of an event, a place, a person, or thing. The " +
    "writing will be such that it will set a mood or describe something in such detail that if the reader saw it, " +
    "they would recognize it. Descriptive writing will bring words to life and makes the text interesting" :: "es " +
    "an impression in the reader’s mind of an event, a place, a person, or thing. The writing will be such that it " +
    "will set a mood or describe something in such detail that if the reader saw it, they would recognize it. " +
    "Descriptive writing will bring words to life and makes the text interesting. Some examples of descriptive text " +
    "include:" :: "The sunset filled the entire sky with the deep color of rubies, setting the clouds ablaze." ::
    "The waves crashed and danced along the shore, moving up and down in a graceful and gentle rhythm like they " +
    "were dancing." :: "The painting was a field of flowers, with deep and rich blues and yellows atop vibrant " +
    "green stems that seemed to beckon you to reach right in and pick them." :: "The old man was stooped and bent, " +
    "his back making the shape of a C and his head bent so far forward that his beard would nearly have touched " +
    "his knobby knees had he been just a bit taller." :: "His deep and soulful blue eyes were like the color of " +
    "the ocean on the clearest day you can ever imagine." :: "The soft fur of the dog felt like silk against my " +
    "skin and her black coloring glistened as it absorbed the sunlight, reflecting it back as a perfect, deep, " +
    "dark mirror" :: "Descriptive Text in Literature" :: "Because descriptive text is so powerful, many examples " +
    "of it can be found in famous literature and poetry. In this excerpt from Jamaica Inn by Daphne du Maurier, " +
    "notice the writer’s choice of adjectives, adverbs, and verbs." :: Nil

  val lens: Seq[Int] = texts.map(_.length)
  val indexes: Seq[Int] = lens.scanLeft(0)(_ + _).init
  val htmlMock: Seq[PosElem] = paths.zip(texts).zip(indexes) map { case ((p, t), i) => PosElem(p, t, i) }

  /** Create a highlight over elements [fromEl,toEl] (end inclusive) starting with initIndx in fromEl element. */
  def makeHighlight(fromEl: Int, toEl: Int, initIndx: Int, endLen: Int): Highlight = {
    assert(fromEl <= toEl)
    val slice: Seq[PosElem] = htmlMock.slice(fromEl, toEl + 1)
    val els: Seq[PosElem] = {
      val h = slice.head
      val wh = PosElem(h.path, h.text.substring(initIndx), h.index + initIndx) +: slice.tail // change head start index
      val l = wh.last
      wh.init :+ PosElem(l.path, l.text.substring(0, endLen), l.index) // change last end index
    }
    Highlight(
      constructUserId(),
      markId = constructMarkId(),
      pos = Highlight.Position(els),
      preview = Highlight.Preview("", ("" /: els) (_ + _.text), ""))
  }

  "HighlightIntersectionService" should "(UNIT) merge same-element pieces of text in a highlight" in {

    val elementsWithRepetitions: Seq[PosElem] =
      PosElem(paths.head, texts.head, 0) ::
      PosElem(paths(1), texts(1).substring(0, 40), 0) ::
      PosElem(paths(1), texts(1).substring(40), 40) ::
      PosElem(paths(2), texts(2).substring(0, 30), 0) ::
      PosElem(paths(2), texts(2).substring(30), 30) ::
      PosElem(paths(3), texts(3), 0) :: Nil

    val mergedElems: Seq[PosElem] =
      PosElem(paths.head, texts.head, 0) ::
      PosElem(paths(1), texts(1), 0) ::
      PosElem(paths(2), texts(2), 0) ::
      PosElem(paths(3), texts(3), 0) :: Nil

    Position(elementsWithRepetitions).mergeSameElems().elements shouldEqual mergedElems
  }

  it should "(UNIT) case 1: join highlight with intersection on 1 element with text overlap" in {

    val length = htmlMock(5).text.length
    val highlightA = makeHighlight(2, 5, 0, length - 10)
    val highlightB = makeHighlight(5, 7, length - 20, 10)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe true
    highlightA.startsWith(highlightB).nonEmpty shouldBe false

    val u = highlightA.union(highlightB)

    val intended = makeHighlight(2, 7, 0, 10)
    u.pos shouldEqual intended.pos
    u.preview shouldEqual intended.preview
  }

  it should "(UNIT) case 2: join highlights with intersection on 2 elements with text overlap" in {

    val length = htmlMock(7).text.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(6, 8, 5, 10)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe true
    highlightA.startsWith(highlightB).nonEmpty shouldBe false

    val u = highlightA.union(highlightB)

    val intended = makeHighlight(1, 8, 0, 10)
    u.pos shouldEqual intended.pos
    u.preview shouldEqual intended.preview
  }

  it should "(UNIT) case 3: join highlights with intersection on all elements" in {

    val length = htmlMock(7).text.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(5, 7, 0, length - 10)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe true
    highlightA.startsWith(highlightB).nonEmpty shouldBe false

    val u = highlightA.union(highlightB)

    val intended = makeHighlight(1, 7, 0, length - 10)
    u.pos shouldEqual intended.pos
    u.preview shouldEqual intended.preview
  }

  it should "(UNIT) case 4: join highlights with intersection in the only element with text overlap" in {

    val length = htmlMock(7).text.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(7, 7, 10, length - 35)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe true
    highlightA.startsWith(highlightB).nonEmpty shouldBe false

    val u = highlightA.union(highlightB)

    val intended = makeHighlight(1, 7, 0, length - 25)
    u.pos shouldEqual intended.pos
    u.preview shouldEqual intended.preview
  }

  it should "(UNIT) case 5: detect subset highlight with intersection in the only edge element" in {

    val length = htmlMock(7).text.length
    val highlightA = makeHighlight(1, 7, 0, length - 10)
    val highlightB = makeHighlight(7, 7, 10, length - 20)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe true
    highlightB.startsWith(highlightA).nonEmpty shouldBe true
    highlightA.startsWith(highlightB).nonEmpty shouldBe false
  }

  it should "(UNIT) case 6: detect subset highlight with intersection in 2 inside elements" in {

    val length = htmlMock(7).text.length
    val highlightA = makeHighlight(1, 7, 20, length - 10)
    val highlightB = makeHighlight(5, 6, 10, 20)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe true
    highlightB.startsWith(highlightA).nonEmpty shouldBe false
    highlightA.startsWith(highlightB).nonEmpty shouldBe false
  }

  it should "(UNIT) case 7: detect non-intersecting highlight in 1 element overlapped but no text overlap" in {

    val length = htmlMock(5).text.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 6, length - 10, 20)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe false
    highlightA.startsWith(highlightB).nonEmpty shouldBe false
  }

  it should "(UNIT) case 8: detect non-subset non-intersection highlight in single element overlapped but no text " +
    "overlap" in {

    val length = htmlMock(5).text.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 5, length - 10, 10)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe false
    highlightA.startsWith(highlightB).nonEmpty shouldBe false
  }

  it should "(UNIT) case 9: detect non-intersecting same element highlights without text overlap" in {

    val highlightA = makeHighlight(3, 3, 0, 5)
    val highlightB = makeHighlight(3, 3, 10, 15)

    highlightA.isSubseq(highlightB) shouldBe false
    highlightB.isSubseq(highlightA) shouldBe false
    highlightB.startsWith(highlightA).nonEmpty shouldBe false
    highlightA.startsWith(highlightB).nonEmpty shouldBe false
  }

  it should "(UNIT) case 10: correctly merge same elements in highlight position sequence" in {
    val highlight = makeHighlight(1, 2, 10, htmlMock(2).text.length - 10)
    val es = highlight.pos.elements

    // slice the head into 3 separate elements
    val sliced = es.head.copy(text = es.head.text.substring( 0, 19)) +:
                 es.head.copy(text = es.head.text.substring(19, 39), index = es.head.index + 19) +:
                 es.head.copy(text = es.head.text.substring(39)    , index = es.head.index + 39) +:
                 es.tail

    Position(sliced).mergeSameElems() shouldEqual highlight.pos
  }

  it should "(UNIT) case 11: issue #215 (and #178)" in {

    // first 'o' of elems0 is index 1
    val elems0 = Seq(    //  01 3 5 7 911 3 5 7 921 3 5 7 931 3 5 7 941
      PosElem("/html/p[23]", "o be wary if you hear people within the ", 1),
                         //    41 3 5 7 951 3
      PosElem("/html/p[23]/a", "media bubble", 41),
                         //           53 5
      PosElem("/html/p[23]/a[2]/sup", "13", 53),
                         //  55 7 961 3 5 7 971 3 5 7 981 3 5 7 991 3 5 7
      PosElem("/html/p[23]", " assert that “everyone” presumed Clinton wa", 55))

    // first 'n' of elems1 (and second to last 'n' of elems0) is index 91
    val elems1 = Seq(    //  91 3 5 78
      PosElem("/html/p[23]", "nton wa", 91),
                         //  98901 3 5 7 911 3 5 7 921 3 5 7 931 3 5 7 9411 3 5 7 951 3 5 7 961 3 5 7 9711 3 5 78
      PosElem("/html/p[23]", "s sure to win. Instead, that presumption reflected elite groupthink — and it came ", 98),
                         //    78981 3 5
      PosElem("/html/p[23]/i", "despite", 178),
                         //  85 7 991 3 5 7 901 3 5 7
      PosElem("/html/p[23]", " the polls as much as ", 185))

    val pos0 = Highlight.Position(elems0)
    val pos1 = Highlight.Position(elems1)

    val prv0 = Highlight.Preview("S", "o be wary if you hear people within the media bubble13 assert that “everyone” presumed Clinton wa", "s sure to win. Instead, that presumption reflected")
    val prv1 = Highlight.Preview(" assert that “everyone” presumed Cli", "nton was sure to win. Instead, that presumption reflected elite groupthink — and it came despite the polls as much as ", "because of the polls. There was a bewilderingly large array of polling")

    val hl0 = Highlight(constructUserId(), markId = "case11markId", pos = pos0, preview = prv0)
    val hl1 = Highlight(      hl0.usrId  , markId =    hl0.markId , pos = pos1, preview = prv1)

    hlightsDao.insert(hl0).futureValue
    val merged = hlIntersectionSvc.add(hl1).futureValue

    val expected = "o be wary if you hear people within the media bubble13 assert that “everyone” presumed Clinton was sure to win. Instead, that presumption reflected elite groupthink — and it came despite the polls as much as "
    merged.preview.text shouldEqual expected
  }

  it should "(UNIT) case 12: chrome-extension issue #35" in {

    val sharedPath = "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[3]"

    val json0: JsValue = Json.parse(
      """
        |{ "usrId" : "44444444-4444-4444-4444-444444444444", "id" : "issue35hl0", "markId" : "issue35test", "pos" : { "elements" : [
        |
        |{ "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]",
        |"text" : "If I set up an ExecutorService like this:", "index" : 3885, "outerAnchors" : { "left" : "tted to the ExecutorService, the level goes up.\n\n\n", "right" : "\n\nnew ThreadPoolExecutor(\n  5, // core pool size\n " }, "anchors" : { "left" : "\n", "right" : "" }, "neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div", "cssSelector" : "#Blog1 .uncustomized-post-template.hentry.post .post-header", "elementText" : "\n\n" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[3]", "cssSelector" : "#Blog1 .uncustomized-post-template.hentry.post .post-footer", "elementText" : "\n\n\nPosted by\n\n\n\nJessiTRON\n\n\n\n\nat\n\n8:47 PM\n\n\n\n\n\n\n\n\n\n\n\nEmail ThisBlogThis!Share to TwitterShare to FacebookShare to Pinterest\n\n\n\n\nLabels:\nconcurrency,\nJava,\nscala\n\n\n\n\n\n\n" } }, "cssSelector" : "#post-body-3913631965276660434" },
        |
        |{ "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[3]", "text" : "new ThreadPool", "index" : 0, "outerAnchors" : { "left" : " up.\n\n\nIf I set up an ExecutorService like this:\n\n", "right" : "Executor(\n  5, // core pool size\n  8, // max pool " }, "anchors" : { "left" : "", "right" : "Executor(" }, "neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/div[9]", "cssSelector" : "#post-body-3913631965276660434 .separator", "elementText" : "\n" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "  5, // core pool size" } }, "cssSelector" : "#post-body-3913631965276660434 span" } ] },
        |"preview" : { "lead" : "tted to the ExecutorService, the level goes up.\n\n\n", "text" : "If I set up an ExecutorService like this:\n\nnew ThreadPool", "tail" : "Executor(\n  5, // core pool size\n  8, // max pool " }, "timeFrom" : 1537302155273, "timeThru" : 9223372036854775807,
        |
        |"pageCoord" : { "x" : 0.13734049697783748, "y" : 0.06582579185520362 }, "nSharedTo" : 0, "nSharedFrom" : 0 }
      """.stripMargin)

    val json1: JsValue = Json.parse(
      """
        |{ "usrId" : "44444444-4444-4444-4444-444444444444", "id" : "issue35hl1", "markId" : "issue35test", "pos" : { "elements" : [
        |
        |{ "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[3]",
        |"text" : "PoolExecutor(", "index" : 10,
        |"outerAnchors" : { "left" : "I set up an ExecutorService like this:\n\nnew Thread", "right" : "Executor(\n  5, // core pool size\n  8, // max pool " },
        |"anchors" : { "left" : "new Thread", "right" : "" },
        |"neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/div[9]", "cssSelector" : "#post-body-3913631965276660434 .separator", "elementText" : "\n" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "  5, // core pool size" } },
        |"cssSelector" : "#post-body-3913631965276660434 span" },
        |
        |{ "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]/span", "text" : "5", "index" : 0, "outerAnchors" : { "left" : "utorService like this:\n\nnew ThreadPoolExecutor(\n  ", "right" : ", // core pool size\n  8, // max pool size, for at " }, "anchors" : { "left" : "", "right" : "" }, "neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[3]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "new ThreadPoolExecutor(" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]/span[2]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "core pool size" } }, "cssSelector" : "#post-body-3913631965276660434 span" }, { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]", "text" : ", // ", "index" : 3, "outerAnchors" : { "left" : "torService like this:\n\nnew ThreadPoolExecutor(\n  5", "right" : "core pool size\n  8, // max pool size, for at most " }, "anchors" : { "left" : "", "right" : "" }, "neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[3]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "new ThreadPoolExecutor(" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[5]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "  8, // max pool size, for at most (8 - 5 = 3) red threads" } }, "cssSelector" : "#post-body-3913631965276660434 span" }, { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]/span[2]", "text" : "core pool s", "index" : 0, "outerAnchors" : { "left" : "rvice like this:\n\nnew ThreadPoolExecutor(\n  5, // ", "right" : "ize\n  8, // max pool size, for at most (8 - 5 = 3)" }, "anchors" : { "left" : "", "right" : "ize" }, "neighbors" : { "left" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[4]/span", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "5" }, "right" : { "path" : "/html/body/div[3]/div[2]/div[2]/div[2]/div[2]/div[2]/div[2]/div/div[4]/div/div/div/div/div/div/div/div/div/div[2]/span[5]", "cssSelector" : "#post-body-3913631965276660434 span", "elementText" : "  8, // max pool size, for at most (8 - 5 = 3) red threads" } }, "cssSelector" : "#post-body-3913631965276660434 span" } ] },
        |"preview" : { "lead" : "I set up an ExecutorService like this:\n\nnew Thread", "text" : "PoolExecutor(\n  5, // core pool s", "tail" : "ize\n  8, // max pool size, for at most (8 - 5 = 3)" }, "timeFrom" : 1537302217965, "timeThru" : 9223372036854775807, "pageCoord" : { "x" : 0.13734049697783748, "y" : 0.7273642533936652 }, "nSharedTo" : 0, "nSharedFrom" : 0 }
      """.stripMargin)

    import com.hamstoo.models.HighlightFormatters._
    val hl0 = json0.as[Highlight]
    val hl1 = json1.as[Highlight]

    hlightsDao.insert(hl0).futureValue
    val merged = hlIntersectionSvc.add(hl1).futureValue

    val expected = "If I set up an ExecutorService like this:\n\nnew ThreadPoolExecutor(\n  5, // core pool s"
    merged.preview.text shouldEqual expected
  }
}
