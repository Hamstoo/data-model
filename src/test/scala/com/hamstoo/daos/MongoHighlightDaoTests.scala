package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Highlight, Mark, PageCoord}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper, generateDbId}
import org.scalatest.OptionValues


class MongoHighlightDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with OptionValues
    with FutureHandler
    with TestHelper {

  lazy val highlightDao = new MongoHighlightDao(getDB)

  val usrId: UUID = UUID.randomUUID()
  val markId: String = generateDbId(Mark.ID_LENGTH)

  val h = Highlight(usrId = usrId, markId = markId, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))

  "MongoHighlightDao" should "* (UNIT) insert highlights" in {
    highlightDao.create(h).futureValue shouldEqual {}
  }

  // because of dropping "bin-usrId-1-uPref-1" index
  it should "* (UNIT) retrieve highlights by id" ignore {
    highlightDao.retrieve(h.usrId, h.id).futureValue.value shouldEqual h
  }

  it should "* (UNIT) retrieve highlights by markId" in {
    highlightDao.retrieveByMarkId(h.usrId, h.markId).futureValue shouldEqual Seq(h)
  }

  it should "* (UNIT) update highlights" in {
    val newPos = Highlight.Position(Nil, 2)

    highlightDao.update(h.usrId, h.id, pos = newPos, prv = h.preview, coord = h.pageCoord).futureValue.pos shouldEqual newPos
  }

  it should "* (UNIT) delete highlight" in {
    highlightDao.delete(h.usrId, h.id).futureValue shouldEqual {}
    highlightDao.retrieveByMarkId(h.usrId, h.markId).futureValue shouldEqual Nil
  }
}
