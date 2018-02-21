package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Highlight, PageCoord, User}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues

/**
  * Unit tests for all (basically CRUD) methods of MongoHighlightDao class
  */
class MongoHighlightDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with OptionValues
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  val userId: UUID = constructUserId()
  val markId: String = constructMarkId()
  val h = Highlight(usrId = userId, markId = markId, pos = Highlight.Position(Nil),
                    pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))

  "MongoHighlightDao" should "(UNIT) insert highlights" in {
    hlightsDao.insert(h).futureValue shouldEqual h
  }

  /*it should "(UNIT) retrieve highlights by id" in {
    hlightsDao.retrieve(h.usrId, h.id).futureValue.get shouldEqual h
  }*/

  it should "(UNIT) retrieve highlights by markId" in {
    hlightsDao.retrieve(User(h.usrId), h.markId).futureValue shouldEqual Seq(h)
  }

  it should "(UNIT) update highlights" in {
    val newPos = Highlight.Position(Seq(Highlight.PositionElement("", "", 0)))
    hlightsDao.update(h.usrId, h.id, pos = newPos, prv = h.preview, coord = h.pageCoord).futureValue.pos shouldEqual newPos
  }

  it should "(UNIT) delete highlight" in {
    hlightsDao.delete(h.usrId, h.id).futureValue shouldEqual {}
    hlightsDao.retrieve(User(h.usrId), h.markId).futureValue shouldEqual Nil
  }
}
