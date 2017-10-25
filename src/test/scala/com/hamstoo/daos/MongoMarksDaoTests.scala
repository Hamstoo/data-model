package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.TestHelper
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global

class MongoMarksDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  lazy val marksDao = new MongoMarksDao(getDB)

  val uuid1 = UUID.randomUUID()
  val uuid2 = UUID.randomUUID()

  val tagSet = Set("tag1, tag2")
  val pubRepr = Some("repr")

  val newMarkData = MarkData("a subject1", Some("https://github.com"))

  val m1 = Mark(
    uuid1,
    mark = MarkData("a subject", Some("http://hamstoo.com"), tags = Some(tagSet), comment = Some("Query")),
    pubRepr = pubRepr)
  val m2 = Mark(uuid2, mark = MarkData("a subject1", Some("http://hamstoo.com")))

  val m3 = Mark(uuid2, mark = MarkData("a subject2", Some("http://hamstoo.com")))
  val m4 = Mark(uuid2, id = m3.id, mark = MarkData("a subject2", Some("http://hamstoo.com")), timeThru = Long.MaxValue - 1)

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert stream of mark" in {
    val markStream = m2 #:: m3 #:: Stream.empty[Mark]

    marksDao.insertStream(markStream).futureValue shouldEqual 2
  }

  it should "(UNIT) retrieve by uuid and id" in {
    marksDao.retrieve(uuid1, m1.id).futureValue.value shouldEqual m1
  }

  it should "(UNIT) retrieve by uuid" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m2.id, m3.id)
  }

  it should "(UNIT) retrieve all by uuid and id" in {
    marksDao.insert(m4).futureValue shouldEqual m4

    marksDao.retrieveAllById(m3.id).futureValue.map(_.mark) shouldEqual Seq(m3.mark, m4.mark)
  }

  it should "(UNIT) retrieve by uuid and url" in {
    val url = "http://hamstoo.com"

    marksDao.retrieveByUrl(url, uuid1).futureValue.value shouldEqual m1
  }

  it should "(UNIT) retrieve by uuid and tags" in {
    marksDao.retrieveTagged(uuid1, tagSet).futureValue shouldEqual Seq(m1)
  }

  it should "(UNIT) retrieve repred marks by uuid and tag set" in {
    marksDao.retrieveRepred(uuid1, tagSet).futureValue.map(_.id) shouldEqual Seq(m1.id)
  }

  it should "(UNIT) retrieve mark tags by uuid" in {
    marksDao.retrieveTags(uuid1).futureValue shouldEqual tagSet
  }

  // depend on index text query
  it should "(UNIT) search marks by uuid, query and tags" ignore {
    marksDao.search(uuid1, "Query", tagSet).futureValue shouldEqual Seq(m1)
  }

  it should "(UNIT) update marks by uuid, id, markData" in {
    marksDao.update(uuid1, m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }





//  it should "findMissingReprs, both current and not" in {
//
//    marksDao.insert(mark).futureValue shouldEqual mark
//
//    val newSubj = "a NEW subject"
//    marksDao.update(mark.userId, mark.id, mark.mark.copy(subj = "a NEW subject")).futureValue.mark.subj shouldEqual newSubj
//
//    marksDao.findMissingReprs(1000000).futureValue.count(_.userId == mark.userId) shouldEqual 2
//  }
//
//  it should "obey its `bin-urlPrfx-1-pubRepr-1` index" in {
//    val m = Mark(UUID.randomUUID(), mark = MarkData("crazy url subject", Some(crazyUrl)))
//    val reprId = generateDbId(Representation.ID_LENGTH)
//
//    for {
//      _ <- marksDao.insert(m)
//      _ <- marksDao.updatePublicReprId(m.id, m.timeFrom, reprId)
//      mInserted <- marksDao.retrieve(m.userId, m.id)
//      _ = mInserted.get.pubRepr.get shouldEqual reprId
//    } yield {}
//  }
}
