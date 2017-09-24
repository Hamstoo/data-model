package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Highlight.{HLPos, HLPreview}
import com.hamstoo.models.{Highlight, PageCoord}
import com.hamstoo.utils.TestHelper

import scala.util.Random

class MongoHighlightDaoSpec extends TestHelper {

  lazy val highlightDao = new MongoHighlightDao(getDB)

  "MongoHighlightDai" should "* return correctly sorted list of highlights" in {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()

      val h1 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = HLPreview("first", "", ""))
      val h2 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = Some(PageCoord(0.7, 0.6)), preview = HLPreview("second", "", ""))
      val h3 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = Some(PageCoord(0.9, 0.5)), preview = HLPreview("third", "", ""))

      highlightDao.create(h1).futureValue shouldEqual {}
      highlightDao.create(h2).futureValue shouldEqual {}
      highlightDao.create(h3).futureValue shouldEqual {}

      highlightDao.receiveSortedByPageCoord(h1.url, h1.usrId).futureValue.map(_.preview) shouldEqual Seq(h2.preview, h1.preview, h3.preview)
    }
  }
}
