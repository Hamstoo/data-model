package com.hamstoo.services

import java.util.UUID

import com.hamstoo.daos.MongoHighlightDao
import com.hamstoo.models.{Highlight, PageCoord}
import com.hamstoo.models.Highlight.{PositionElement => PosElem}
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Tests of highlights intersection code.
  */
class HighlightsIntersectionServiceTests extends FlatSpecWithMatchers with MockitoSugar with FutureHandler {

  val hlightsDao: MongoHighlightDao = mock[MongoHighlightDao]
  val hlIntersectionSvc: HighlightsIntersectionService = new HighlightsIntersectionService(hlightsDao)

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
    val slice: Seq[PosElem] =
      htmlMock slice(fromEl, toEl + 1) map { case (p, t) => PosElem(p, t) }
    val els: Seq[PosElem] = {
      val h = slice.head
      val wh = PosElem(h.path, h.text substring initIndx) +: slice.tail
      val l = wh.last
      wh.init :+ PosElem(l.path, l.text substring(0, endLen))
    }
    Highlight(
      constructUserId(),
      markId = constructMarkId(),
      pos = Highlight.Position(els, initIndx),
      preview = Highlight.Preview("", ("" /: els) (_ + _.text), ""))
  }

  "HighlightIntersectionService" should "(UNIT) merge same-element pieces of text in a highlight" in {

    val elementsWithRepetitions: Seq[PosElem] =
      PosElem(paths.head, texts.head) ::
      PosElem(paths(1), texts(1).substring(0, 40)) ::
      PosElem(paths(1), texts(1).substring(40)) ::
      PosElem(paths(2), texts(2).substring(0, 30)) ::
      PosElem(paths(2), texts(2).substring(30)) ::
      PosElem(paths(3), texts(3)) :: Nil

    val mergedElems: Seq[PosElem] =
      PosElem(paths.head, texts.head) ::
      PosElem(paths(1), texts(1)) ::
      PosElem(paths(2), texts(2)) ::
      PosElem(paths(3), texts(3)) :: Nil

    hlIntersectionSvc mergeSameElems elementsWithRepetitions shouldEqual mergedElems
  }

  it should "(UNIT) case 1: join highlight with intersection on 1 element with text overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(2, 5, 0, length - 10)
    val highlightB = makeHighlight(5, 7, length - 20, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1

    val (positionUnion, previewUnion, _) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(2, 7, 0, 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 2: join highlights with intersection on 2 elements with text overlap" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(6, 8, 5, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1

    val (positionUnion, previewUnion, _) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 8, 0, 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 3: join highlights with intersection on all elements" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(5, 7, 0, length - 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1

    val (positionUnion, previewUnion, _) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 7, 0, length - 10)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 4: join highlights with intersection in the only element with text overlap" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 30)
    val highlightB = makeHighlight(7, 7, 10, length - 35)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1

    val (positionUnion, previewUnion, _) = hlIntersectionSvc.union(highlightA, highlightB)

    val intended = makeHighlight(1, 7, 0, length - 25)
    positionUnion shouldEqual intended.pos
    previewUnion shouldEqual intended.preview
  }

  it should "(UNIT) case 5: detect subset highlight with intersection in the only edge element" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 10)
    val highlightB = makeHighlight(7, 7, 10, length - 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe -1
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1
  }

  it should "(UNIT) case 6: detect subset highlight with intersection in 2 inside elements" in {

    val length = htmlMock(7)._2.length
    val highlightA = makeHighlight(1, 7, 20, length - 10)
    val highlightB = makeHighlight(5, 6, 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe -1
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 7: detect non-intersecting highlight in 1 element overlapped but no text overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 6, length - 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 8: detect non-subset non-intersection highlight in single element overlapped but no text " +
    "overlap" in {

    val length = htmlMock(5)._2.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 5, length - 10, 10)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 9: detect non-intersecting same element highlights without text overlap" in {

    val highlightA = makeHighlight(3, 3, 0, 5)
    val highlightB = makeHighlight(3, 3, 10, 15)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 10: correctly merge same elements in highlight position sequence" in {
    val highlight = makeHighlight(1, 2, 10, htmlMock(2)._2.length - 10)
    val es = highlight.pos.elements
    val sliced = highlight.copy(pos = highlight.pos.copy(
      elements = es.head.copy(text = es.head.text.substring(0, 20)) +:
        es.head.copy(text = es.head.text.substring(20, 40)) +:
        es.head.copy(text = es.head.text.substring(40)) +: es.tail))

    hlIntersectionSvc.mergeSameElems(sliced.pos.elements) shouldEqual highlight.pos.elements
  }

  it should "(UNIT) case 11: issue #215 (and #178)" in {

    val elems0 = Seq(
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", "So be wary if you hear people within the "),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]/a", "media bubble"),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]/a[2]/sup", "13"),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", " assert that “everyone” presumed Clinton was"))

    val elems1 = Seq(
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", "linton wa"),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", "s sure to win. Instead, that presumption reflected elite groupthink — and it came "),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]/i", "despite"),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", " the polls as much as "),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]/i[2]", "because of"),
      PosElem("/html/body/div[6]/div/div/div/article/div/p[23]", " the polls"))
    
    val pos0 = Highlight.Position(elems0, 1)
    val pos1 = Highlight.Position(elems1, 91)
    
    val hl0 = Highlight(constructUserId(), markId = "case11markId", pos = pos0, preview = Highlight.Preview("", "", ""))
    val hl1 = Highlight(      hl0.usrId  , markId =    hl0.markId , pos = pos1, preview = Highlight.Preview("", "", ""))

    // these are the only 2 methods of hlightsDao that should be invoked, a NPE will occur if others are invoked also
    when(hlightsDao.retrieveByMarkId(any[UUID], anyString())).thenReturn(Future.successful(Seq(hl0)))
    when(hlightsDao.update(any[UUID], anyString, any[Highlight.Position],
                           any[Highlight.Preview], any[Option[PageCoord]]))
      .thenAnswer { invocation: InvocationOnMock =>
        Future.successful(hl0.copy(pos = invocation.getArgument[Highlight.Position](2)))
      }

    val merged = hlIntersectionSvc.add(hl1).futureValue

    true shouldBe false

  }
}
