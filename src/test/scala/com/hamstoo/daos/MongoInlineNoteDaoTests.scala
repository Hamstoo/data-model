package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Annotation, InlineNote, Mark, PageCoord}
import com.hamstoo.utils.{generateDbId, TestHelper}

import scala.util.Random


class MongoInlineNoteDaoTests extends TestHelper {

  lazy val notesDao = new MongoInlineNoteDao(getDB)

  "MongoInlineNoteDao" should "test create comment" in  {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val markId = generateDbId(Mark.ID_LENGTH)
      val c = InlineNote(usrId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))

      notesDao.create(c).futureValue shouldEqual {}
      notesDao.update(c.usrId, c.id, c.pos).futureValue.timeFrom should not equal c.timeFrom
      notesDao.retrieveByMarkId(usrId, markId).futureValue.count(_.usrId == c.usrId) shouldEqual 1
    }
  }

  it should "return correct list of comments for specified user" in {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val markId = generateDbId(Mark.ID_LENGTH)

      val c1 = InlineNote(usrId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.5, 0.5)))
      val c2 = InlineNote(usrId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.6, 0.5)))
      val c3 = InlineNote(usrId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.4, 0.8)))

      notesDao.create(c1).futureValue shouldEqual {}
      notesDao.create(c2).futureValue shouldEqual {}
      notesDao.create(c3).futureValue shouldEqual {}

      notesDao.retrieveByMarkId(c1.usrId, c1.markId).futureValue
        .sortWith(Annotation.sort)
        .map(_.pos.text) shouldEqual Seq(c1.pos.text, c2.pos.text, c3.pos.text)
    }
  }
}
