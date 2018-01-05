package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.INF_TIME
import org.scalatest.OptionValues

/**
  * MongoMarksDao tests.
  */
class MongoMarksDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._

  val uuid1: UUID = constructUserId()
  val uuid2: UUID = constructUserId()

  val tagSet = Some(Set("tag1, tag2"))
  val cmt = Some("Query")
  val repr = Some("repr")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"))

  val m1 = Mark(uuid1, "m1id", MarkData("a subject", Some("http://hamstoo.com"), tags = tagSet, comment = cmt))
  val m2 = Mark(uuid2, "m2id", MarkData("a subject1", Some("http://hamstoo.com"), tags = tagSet), pubRepr = repr, userRepr = repr)
  val m3 = Mark(uuid2, "m3id", MarkData("a subject2", Some("http://hamstoo.com")))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject2", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert stream of mark" in {
    val markStream = m2 #:: m3 #:: Stream.empty[Mark]
    marksDao.insertStream(markStream).futureValue shouldEqual 2
  }

  it should "(UNIT) retrieve" in {
    marksDao.retrieve(User(uuid1), m1.id).futureValue.value shouldEqual m1
  }

  it should "(UNIT) retrieve by uuid" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m2.id, m3.id)
  }

  it should "(UNIT) retrieve mark history" in {
    marksDao.insert(m4).futureValue shouldEqual m4
    marksDao.retrieveInsecureHist(m3.id).futureValue.map(_.mark) shouldEqual Seq(m3.mark, m4.mark)
  }

  it should "(UNIT) retrieve by uuid and url" in {
    val url = "http://hamstoo.com"
    marksDao.retrieveByUrl(url, uuid1).futureValue.value shouldEqual m1
  }

  it should "(UNIT) retrieve by uuid and tags" in {
    marksDao.retrieveTagged(uuid1, tagSet.get).futureValue shouldEqual Seq(m1)
  }

  it should "(UNIT) retrieve represented marks by uuid and tag set" in {
    marksDao.retrieveRepred(uuid2, tagSet.get).futureValue.map(_.id) shouldEqual Seq(m2.id)
  }

  it should "(UNIT) retrieve mark tags by uuid" in {
    marksDao.retrieveTags(uuid1).futureValue shouldEqual tagSet.get
  }

  // depend on index text query (FWC - wtf does this comment mean, and why is it relevant?)
  it should "(UNIT) search marks by uuid, query and tags" in {
    val md1Stub = MarkData(m1.mark.subj, m1.mark.url, tags = m1.mark.tags, comment = m1.mark.comment)
    val m1Stub = m1.copy(mark = md1Stub, aux = m1.aux.map(_.cleanRanges))
    marksDao.search(uuid1, cmt.get, tagSet.get).futureValue shouldEqual Seq(m1Stub)
  }

  it should "(UNIT) update marks by uuid, id, markData" in {
    marksDao.update(User(uuid1), m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) delete mark by uuid, id" in {
    marksDao.delete(uuid1, m1.id :: Nil).futureValue shouldEqual 1
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(uuid1, m1.mark.url.get).futureValue shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, both current and not (update: no longer finding non-current)" in {
    marksDao.findMissingReprs(-1).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) find marks with missing reprs, and be able to limit the quantity returned" in {
    val m5 = m3.copy(id = "m5id")
    marksDao.insert(m5).futureValue
    val expected = Set(m3.id, m5.id)
    expected.contains(marksDao.findMissingReprs(1).futureValue.head.id) shouldBe true
    marksDao.findMissingReprs(2).futureValue.map(_.id).toSet shouldEqual expected
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    val md = MarkData("testSubj", None)
    val m = Mark(userId = UUID.randomUUID(), "testId", mark = md)
    marksDao.insert(m).futureValue shouldEqual m
    marksDao.findDuplicateSubject(m.userId, md.subj).futureValue should not equal None
  }

  /*it should "(UNIT) find marks with missing reprs only once per mark (issue #198)" in {
    var marks: Set[Mark] = marksDao.findMissingReprs(-1).futureValue.toSet
    marks.size shouldEqual 4

    for(_ <- 0 until 10) {
      marks ++= marksDao.findMissingReprs(-1).futureValue
      marks.size shouldEqual 4
    }
  }*/

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
