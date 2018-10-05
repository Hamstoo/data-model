/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, User}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues
import play.api.libs.json.Json

/**
  * Unit tests for all (basically CRUD) methods of MongoInlineNoteDao class
  */
class InlineNoteDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._

  val userId: UUID = constructUserId()
  val markId: String = constructMarkId()
  val c = InlineNote(usrId = userId, markId = markId, pos = inlineNotePos)

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
    val newPos = InlineNote.Position(Some("newtext"), "newpath", Some("newcss"), Some("newnodeval"), 0, 0)
    import com.hamstoo.models.InlineNoteFormatters._
    val json = Json.obj("position" -> newPos)
    notesDao.update(c.usrId, c.id, json).futureValue.pos shouldEqual newPos
  }

  it should "(UNIT) delete inline note" in {
    notesDao.delete(c.usrId, c.id).futureValue should be > 1537302565805L
  }
}
