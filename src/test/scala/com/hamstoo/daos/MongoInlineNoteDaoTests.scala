package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, User}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues

/**
  * Unit tests for all (basically CRUD) methods of MongoInlineNoteDao class
  */
class MongoInlineNoteDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._

  val userId: UUID = constructUserId()
  val markId: String = constructMarkId()
  val c = InlineNote(usrId = userId, markId = markId, pos = InlineNote.Position("sdassd", "sdassd", 0, 0))

  "MongoInlineNotesDao" should "(UNIT) create inline note" in {
    notesDao.insert(c).futureValue shouldEqual c
  }

  /*it should "(UNIT) retrieve inline note by id" in {
    notesDao.retrieve(c.usrId, c.id).futureValue.get shouldEqual c
  }*/

  it should "UNIT) retrieve inline note by markId" in {
    notesDao.retrieve(User(c.usrId), c.markId).futureValue shouldEqual Seq(c)
  }

  it should "(UNIT) update inline note" in {
    val newPos = InlineNote.Position("1", "2", 0, 0)
    notesDao.update(c.usrId, c.id, newPos, None).futureValue.pos shouldEqual newPos
  }

  it should "(UNIT) delete inline note" in {
    notesDao.delete(c.usrId, c.id).futureValue shouldEqual {}
  }
}
