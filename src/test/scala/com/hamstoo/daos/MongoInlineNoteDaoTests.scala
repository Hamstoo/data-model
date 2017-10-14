package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, Mark}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper, generateDbId}
import org.scalatest.OptionValues


class MongoInlineNoteDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  lazy val notesDao = new MongoInlineNoteDao(getDB)

  val usrId: UUID = UUID.randomUUID()
  val markId: String = generateDbId(Mark.ID_LENGTH)

  val c = InlineNote(usrId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))

  "MongoInlineNotesDao" should "* (UNIT) create inline note" in {
    notesDao.create(c).futureValue shouldEqual {}
  }

  // todo: solve reactivemongo.bson.exceptions.DocumentKeyNotFound: The key 'usrId' could not be found in this document or array
  it should "* (UNIT) retrieve inline note by id" ignore {
    notesDao.retrieve(c.usrId, c.id).futureValue.value shouldEqual c
  }

  it should "* (UNIT) retrieve inline note by markId" in {
    notesDao.retrieveByMarkId(c.usrId, c.markId).futureValue shouldEqual Seq(c)
  }

  it should "* (UNIT) update inline note" in {
    val newPos = InlineNote.Position("1", "2", 0, 0)
    notesDao.update(c.usrId, c.id, newPos, None).futureValue.pos shouldEqual newPos
  }

  it should "* (UNIT) delete inline note" in {
    notesDao.delete(c.usrId, c.id).futureValue shouldEqual {}
  }
}