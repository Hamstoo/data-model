package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Annotation, InlineNote, PageCoord}
import com.hamstoo.utils.TestHelper

import scala.util.Random


class MongoCommentDaoSpec extends TestHelper {

  lazy val commentsDao = new MongoInlineNoteDao(getDB)

  "MongoCommentsDao" should "* test create comment" in  {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()
      val c = InlineNote(usrId, url = url, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))

      commentsDao.create(c).futureValue shouldEqual {}

      commentsDao.update(c.usrId, c.id, c.pos).futureValue.timeFrom should not equal c.timeFrom

      commentsDao.retrieveByUrl(usrId, url).futureValue.count(_.usrId == c.usrId) shouldEqual 1
    }
  }

  it should "* return correct list of comments for specified user" in {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()

      val c1 = InlineNote(usrId, url = url, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.5, 0.5)))
      val c2 = InlineNote(usrId, url = url, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.6, 0.5)))
      val c3 = InlineNote(usrId, url = url, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.4, 0.8)))

      commentsDao.create(c1).futureValue shouldEqual {}
      commentsDao.create(c2).futureValue shouldEqual {}
      commentsDao.create(c3).futureValue shouldEqual {}

      commentsDao.retrieveByUrl(c1.usrId, c1.url).futureValue
        .sortWith(Annotation.sort)
        .map(_.pos.text) shouldEqual Seq(c1.pos.text, c2.pos.text, c3.pos.text)
    }
  }
}
