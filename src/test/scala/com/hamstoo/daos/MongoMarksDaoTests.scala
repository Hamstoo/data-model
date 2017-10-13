package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, Representation}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper, generateDbId}

import scala.concurrent.ExecutionContext.Implicits.global

class MongoMarksDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with TestHelper {

  lazy val marksDao = new MongoMarksDao(getDB)

  "MongoMarksDao" should "findMissingReprs, both current and not" in {

    val mark = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))
    marksDao.insert(mark).futureValue shouldEqual mark

    val newSubj = "a NEW subject"
    marksDao.update(mark.userId, mark.id, mark.mark.copy(subj = "a NEW subject")).futureValue.mark.subj shouldEqual newSubj

    marksDao.findMissingReprs(1000000).futureValue.count(_.userId == mark.userId) shouldEqual 2
  }

  it should "obey its `bin-urlPrfx-1-pubRepr-1` index" in {
    val m = Mark(UUID.randomUUID(), mark = MarkData("crazy url subject", Some(crazyUrl)))
    val reprId = generateDbId(Representation.ID_LENGTH)

    for {
      _ <- marksDao.insert(m)
      _ <- marksDao.updatePublicReprId(m.id, m.timeFrom, reprId)
      mInserted <- marksDao.retrieve(m.userId, m.id)
      _ = mInserted.get.pubRepr.get shouldEqual reprId
    } yield {}
  }
}
