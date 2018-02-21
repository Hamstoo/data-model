package com.hamstoo.services

import java.util.UUID

import com.hamstoo.daos.MongoHighlightDao
import com.hamstoo.models.{Highlight, PageCoord, User}
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

  val lens: Seq[Int] = texts.map(_.length)
  val indexes: Seq[Int] = lens.inits.toList.reverse.map(_.sum).init

  val htmlMock: Seq[((String, String), Int)] = paths.zip(texts).zip(indexes)

  def makeHighlight(fromEl: Int, toEl: Int, initIndx: Int, endLen: Int): Highlight = {
    assert(fromEl <= toEl)
    val slice: Seq[PosElem] =
      htmlMock.slice(fromEl, toEl + 1) map { case ((p, t), i) => PosElem(p, t, i) }
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

    hlIntersectionSvc.mergeSameElems(elementsWithRepetitions) shouldEqual mergedElems
  }

  it should "(UNIT) case 1: join highlight with intersection on 1 element with text overlap" in {

    val length = htmlMock(5)._1._2.length
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

    val length = htmlMock(7)._1._2.length
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

    val length = htmlMock(7)._1._2.length
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

    val length = htmlMock(7)._1._2.length
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

    val length = htmlMock(7)._1._2.length
    val highlightA = makeHighlight(1, 7, 0, length - 10)
    val highlightB = makeHighlight(7, 7, 10, length - 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe -1
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe -1
  }

  it should "(UNIT) case 6: detect subset highlight with intersection in 2 inside elements" in {

    val length = htmlMock(7)._1._2.length
    val highlightA = makeHighlight(1, 7, 20, length - 10)
    val highlightB = makeHighlight(5, 6, 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 1
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe -1
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 7: detect non-intersecting highlight in 1 element overlapped but no text overlap" in {

    val length = htmlMock(5)._1._2.length
    val highlightA = makeHighlight(1, 5, 20, length - 20)
    val highlightB = makeHighlight(5, 6, length - 10, 20)

    hlIntersectionSvc.isSubset(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isSubset(highlightB.pos, highlightA.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightA.pos, highlightB.pos) shouldBe 0
    hlIntersectionSvc.isEdgeIntsc(highlightB.pos, highlightA.pos) shouldBe 0
  }

  it should "(UNIT) case 8: detect non-subset non-intersection highlight in single element overlapped but no text " +
    "overlap" in {

    val length = htmlMock(5)._1._2.length
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
    val highlight = makeHighlight(1, 2, 10, htmlMock(2)._1._2.length - 10)
    val es = highlight.pos.elements
    val sliced = highlight.copy(pos = highlight.pos.copy(
      elements = es.head.copy(text = es.head.text.substring(0, 20)) +:
        es.head.copy(text = es.head.text.substring(20, 40)) +:
        es.head.copy(text = es.head.text.substring(40)) +: es.tail))

    hlIntersectionSvc.mergeSameElems(sliced.pos.elements) shouldEqual highlight.pos.elements
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

    // these are the only 2 methods of hlightsDao that should be invoked, a NPE will occur if others are invoked also
    when(hlightsDao.retrieve(any[Option[User]], anyString())).thenReturn(Future.successful(Seq(hl0)))
    when(hlightsDao.update(any[UUID], anyString, any[Highlight.Position],
                           any[Highlight.Preview], any[Option[PageCoord]]))
      .thenAnswer { invocation: InvocationOnMock =>
        Future.successful(hl0.copy(pos = invocation.getArgument[Highlight.Position](2),
                                   preview = invocation.getArgument[Highlight.Preview](3)))
      }

    val merged = hlIntersectionSvc.add(hl1).futureValue

    val expected = "o be wary if you hear people within the media bubble13 assert that “everyone” presumed Clinton was sure to win. Instead, that presumption reflected elite groupthink — and it came despite the polls as much as "
    merged.preview.text shouldEqual expected
  }
}
