/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues

/**
  * Unit tests for all (basically CRUD) methods of MongoHighlightDao class
  */
class HighlightDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with OptionValues
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  val userId: UUID = constructUserId()
  val markId: String = constructMarkId()
  val h = Highlight(usrId = userId, markId = markId, pos = Highlight.Position(Nil),
                    pageCoord = Some(PageCoord(0.5, 0.6)), preview = Highlight.Preview("first", "", ""))
  val usrRepr: ReprInfo = ReprInfo("reprId", ReprType.USER_CONTENT)
  val m = Mark(userId, markId, mark = MarkData("some subj", None), reprs = Seq(usrRepr))

  "MongoHighlightDao" should "(UNIT) insert highlights, unset user content repr" in {
    marksDao.insert(m).futureValue shouldEqual m
    marksDao.retrieveById(User(userId), markId).futureValue.value.userContentRepr should not equal None

    hlightsDao.insert(h).futureValue shouldEqual h

    marksDao.retrieveById(User(userId), markId).futureValue.value.userContentRepr shouldEqual None
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
    marksDao.insertReprInfo(m.id, usrRepr).futureValue shouldEqual {}
    marksDao.retrieveById(User(m.userId), m.id).futureValue.value.userContentRepr should not equal None

    hlightsDao.delete(h.usrId, h.id).futureValue shouldEqual {}
    hlightsDao.retrieve(User(h.usrId), h.markId).futureValue shouldEqual Nil

    marksDao.retrieveById(User(m.userId), m.id).futureValue.value.userContentRepr shouldEqual None
  }
}
