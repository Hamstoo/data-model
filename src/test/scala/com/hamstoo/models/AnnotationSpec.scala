package com.hamstoo.models

import java.util.UUID

import com.hamstoo.test.FlatSpecWithMatchers

/**
  * Unit tests for annotations sorting
  */
class AnnotationSpec extends FlatSpecWithMatchers {

  import AnnotationSpec._

  "Annotation sorting" should "(UNIT) sort correctly with defined page coordinates" in {

    import FullyDefined._

    noteSeq.sortWith(Annotation.sort).map(_.pageCoord) shouldEqual Seq(c1.pageCoord, c2.pageCoord, c3.pageCoord)

    highlightSeq.sortWith(Annotation.sort).map(_.pageCoord) shouldEqual Seq(h3.pageCoord, h1.pageCoord, h2.pageCoord)

    noteSeq ++ highlightSeq sortWith Annotation.sort map (_.pageCoord) shouldEqual Seq(c1.pageCoord, c2.pageCoord, h3.pageCoord, h1.pageCoord, h2.pageCoord, c3.pageCoord)

  }

  it should "(UNIT) sort correctly with partial undefined page coordinates" in {

    import PartialDefined._

    noteSeq.sortWith(Annotation.sort) shouldEqual Seq(c2, c1, c3)

    highlightSeq.sortWith(Annotation.sort) shouldEqual Seq(h2, h1, h3)

    noteSeq ++ highlightSeq sortWith Annotation.sort shouldEqual Seq(h2, h1, c2, c1, h3, c3)

  }

  it should "(UNIT) sort correctly with totally undefined page coordinates" in {

    import FullyUndefined._

    noteSeq.sortWith(Annotation.sort) shouldEqual noteSeq.reverse

    highlightSeq.sortWith(Annotation.sort) shouldEqual highlightSeq.reverse

    val combinedSeq = noteSeq ++ highlightSeq

    combinedSeq sortWith Annotation.sort shouldEqual combinedSeq.reverse
  }
}

object AnnotationSpec {

  val uuid: UUID = UUID.randomUUID()
  val id = "someId"

  object FullyDefined {

    val c1 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.5, 0.5)))
    val c2 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.6, 0.5)))
    val c3 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.4, 0.8)))

    val noteSeq: Seq[InlineNote] = Seq(c1, c2, c3)

    val h1 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("", "", ""))
    val h2 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.7, 0.6)), preview = Highlight.Preview("", "", ""))
    val h3 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.9, 0.5)), preview = Highlight.Preview("", "", ""))

    val highlightSeq: Seq[Highlight] = Seq(h1, h2, h3)
  }

  object PartialDefined {

    val c1 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.5, 0.5)))
    val c2 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))
    val c3 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.4, 0.8)))

    val noteSeq: Seq[InlineNote] = Seq(c1, c2, c3)

    val h1 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), preview = Highlight.Preview("", "", ""))
    val h2 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), preview = Highlight.Preview("", "", ""))
    val h3 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.9, 0.5)), preview = Highlight.Preview("", "", ""))

    val highlightSeq: Seq[Highlight] = Seq(h1, h2, h3)
  }

  object FullyUndefined {

    val c1 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))
    val c2 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))
    val c3 = InlineNote(usrId = uuid, markId = id, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))

    val noteSeq: Seq[InlineNote] = Seq(c1, c2, c3)

    val h1 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), preview = Highlight.Preview("", "", ""))
    val h2 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), preview = Highlight.Preview("", "", ""))
    val h3 = Highlight(usrId = uuid, markId = id, pos = Highlight.Position(Nil, 0), preview = Highlight.Preview("", "", ""))

    val highlightSeq: Seq[Highlight] = Seq(h1, h2, h3)

  }
}
