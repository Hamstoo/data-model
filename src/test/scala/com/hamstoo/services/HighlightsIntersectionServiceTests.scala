package com.hamstoo.services

import com.hamstoo.daos.MongoHighlightDao
import com.hamstoo.models.{HLPosition, HLPositionElement, Highlight, Mark}
import com.hamstoo.test.FlatSpecWithMatchers
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.utils.{TestHelper, generateDbId}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Tests of highlights intersection code.
  */
class HighlightsIntersectionServiceTests extends FlatSpecWithMatchers with MongoEnvironment with TestHelper {

  val hlightsDao: MongoHighlightDao = new MongoHighlightDao(getDB)
  val hlIntersectionSvc: HighlightsIntersectionService = new HighlightsIntersectionService(hlightsDao)

  val markId = generateDbId(Mark.ID_LENGTH)

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

  val htmlMock: Seq[(String, String)] = paths zip texts

  def makeHighlight(fromEl: Int, toEl: Int, initIndx: Int, endLen: Int): Highlight = {
    assert(fromEl <= toEl)
    val slice: Seq[HLPositionElement] = htmlMock slice(fromEl, toEl + 1) map { case (p, t) => HLPositionElement(p, t) }
    val els: Seq[HLPositionElement] = {
      val h = slice.head
      val wh = HLPositionElement(h.path, h.text substring initIndx) +: slice.tail
      val l = wh.last
      wh.init :+ HLPositionElement(l.path, l.text substring(0, endLen))
    }
    Highlight(userId, markId = markId, pos = HLPosition(els, initIndx), preview = Highlight.Preview("", ("" /: els)(_ + _.text), ""))
  }


  "HighlightIntersectionService" should "(UNIT) merge same-element pieces of text in a highlight" in {

      val elementsWithRepetitions: Seq[HLPositionElement] = HLPositionElement(paths.head, texts.head) ::
        HLPositionElement(paths(1), texts(1).substring(0, 40)) :: HLPositionElement(paths(1), texts(1).substring(40)) ::
        HLPositionElement(paths(2), texts(2).substring(0, 30)) :: HLPositionElement(paths(2), texts(2).substring(30)) ::
        HLPositionElement(paths(3), texts(3)) :: Nil

      val mergedElems: Seq[HLPositionElement] = HLPositionElement(paths.head, texts.head) :: HLPositionElement(paths(1), texts(1)) ::
        HLPositionElement(paths(2), texts(2)) :: HLPositionElement(paths(3), texts(3)) :: Nil

      hlIntersectionSvc mergeSameElems elementsWithRepetitions shouldEqual mergedElems
  }


  it should "(UNIT) case 1: join highlight with intersection on 1 element with text overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(2, 5, 0, length - 10)
    val highlightB = makeHighlight(5, 7, length - 20, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe Some(false)

    val (positionUnion, previewUnion, pageCoord) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(2, 7, 0, 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 2: join highlights with intersection on 2 elements with text overlap" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(6, 8, 5, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe Some(false)

    val (positionUnion, previewUnion, pageCoord) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 8, 0, 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 3: join highlights with intersection on all elements" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(5, 7, 0, length - 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe Some(false)

    val (positionUnion, previewUnion, pageCoord) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 7, 0, length - 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 4: join highlights with intersection in the only element with text overlap" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(7, 7, 10, length - 35)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe Some(false)

    val (positionUnion, previewUnion, pageCoord) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 7, 0, length - 25)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 5: detect subset highlight with intersection in the only edge element" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 10)
    val highlightB = makeHighlight(7, 7, 10, length - 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe Some(false)
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe Some(false)
  }

  it should "(UNIT) case 6: detect subset highlight with intersection in 2 inside elements" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 20, length - 10)
    val highlightB = makeHighlight(5, 6, 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe Some(false)
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe Some(true)
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe None
  }

  it should "(UNIT) case 7: detect non-intersecting highlight in 1 element overlapped but no text overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 6, length - 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe None
  }

  it should "(UNIT) case 8: detect non-subset non-intersection highlight in single element overlapped but no text overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 5, length - 10, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe None
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe None
  }
}
