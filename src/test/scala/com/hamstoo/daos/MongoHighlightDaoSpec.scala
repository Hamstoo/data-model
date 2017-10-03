package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Annotation, Highlight, PageCoord}
import com.hamstoo.utils.TestHelper

import scala.util.Random


class MongoHighlightDaoSpec extends TestHelper {

  lazy val highlightDao = new MongoHighlightDao(getDB)

  "MongoHighlightDao" should "* return correctly sorted list of highlights" in {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()

      val h1 = Highlight(usrId = usrId, url = url, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))
      val h2 = Highlight(usrId = usrId, url = url, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.7, 0.6)), preview = Highlight.Preview("second", "", ""))
      val h3 = Highlight(usrId = usrId, url = url, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.9, 0.5)), preview = Highlight.Preview("third", "", ""))

      highlightDao.create(h1).futureValue shouldEqual {}
      highlightDao.create(h2).futureValue shouldEqual {}
      highlightDao.create(h3).futureValue shouldEqual {}

      highlightDao.retrieveByUrl(h1.usrId, h1.url).futureValue
        .sortWith(Annotation.sort)
        .map(_.preview) shouldEqual Seq(h2.preview, h1.preview, h3.preview)
    }
  }
}
