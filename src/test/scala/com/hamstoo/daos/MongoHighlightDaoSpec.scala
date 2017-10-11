package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Annotation, Highlight, Mark, PageCoord}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper, generateDbId}


class MongoHighlightDaoSpec
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with TestHelper {

  lazy val highlightDao = new MongoHighlightDao(getDB)

  "MongoHighlightDao" should "* return correctly sorted list of highlights" in {
    val usrId = UUID.randomUUID()
    val markId = generateDbId(Mark.ID_LENGTH)

    val h1 = Highlight(usrId = usrId, markId = markId, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))
    val h2 = Highlight(usrId = usrId, markId = markId, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.7, 0.6)), preview = Highlight.Preview("second", "", ""))
    val h3 = Highlight(usrId = usrId, markId = markId, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.9, 0.5)), preview = Highlight.Preview("third", "", ""))

    highlightDao.create(h1).futureValue shouldEqual {}
    highlightDao.create(h2).futureValue shouldEqual {}
    highlightDao.create(h3).futureValue shouldEqual {}

    highlightDao.retrieveByMarkId(h1.usrId, h1.markId).futureValue
      .sortWith(Annotation.sort)
      .map(_.preview) shouldEqual Seq(h2.preview, h1.preview, h3.preview)
  }
}
