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

  val h1 = Highlight(usrId = usrId, markId = markId, pos = Highlight.Position(Nil, 0), pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))

  "MongoHighlightDao" should "* insert highlights" in {
    highlightDao.create(h1).futureValue shouldEqual {}
  }

  // todo: solve reactivemongo.bson.exceptions.DocumentKeyNotFound: The key 'usrId' could not be found in this document or array
  it should "* retrieve highlights by id" ignore {
    highlightDao.retrieve(h1.usrId, h1.id).futureValue.value shouldEqual h1
  }

  it should "* retrieve highlights by markId" in {
    highlightDao.retrieveByMarkId(h1.usrId, h1.markId).futureValue shouldEqual Seq(h1)
  }

  it should "* update highlights" in {
    val newPos = Highlight.Position(Nil, 2)

    highlightDao.update(h1.usrId, h1.id, pos = newPos, prv = h1.preview, coord = h1.pageCoord).futureValue.pos shouldEqual newPos
  }

  it should "* delete highlight" in {
    highlightDao.delete(h1.usrId, h1.id).futureValue shouldEqual {}
    highlightDao.retrieveByMarkId(h1.usrId, h1.markId).futureValue shouldEqual Nil
  }
}
