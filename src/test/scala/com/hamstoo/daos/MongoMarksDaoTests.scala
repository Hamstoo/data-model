package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{MDSearchable, MSearchable, Mark, MarkData}
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
  val pubRepr = Some("repr")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"))

  val m1 = Mark(uuid1, "m1id", MarkData("a subject", Some("http://hamstoo.com"), tags = tagSet, comment = cmt))
  val m2 = Mark(uuid2, "m2id", MarkData("a subject1", Some("http://hamstoo.com"), tags = tagSet), pubRepr = pubRepr)
  val m3 = Mark(uuid2, "m3id", MarkData("a subject2", Some("http://hamstoo.com")))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject2", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

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
    marksDao.retrieveTagged(uuid1, tagSet.get).futureValue shouldEqual Seq(m1)
  }

  it should "(UNIT) retrieve represented marks by uuid and tag set" in {
    val ms2Stub = MSearchable(
      m2.userId,
      m2.id,
      mark = MDSearchable(m2.mark.subj, m2.mark.url, None),
      pubRepr = m2.pubRepr,
      timeFrom = m2.timeFrom)

    val mSearchable = marksDao.retrieveRepred(uuid2, tagSet.get).futureValue
    mSearchable.map(_.userId) shouldEqual Seq(ms2Stub.userId)

    mSearchable.head shouldBe a [MSearchable]
  }

  it should "(UNIT) retrieve mark tags by uuid" in {
    marksDao.retrieveTags(uuid1).futureValue shouldEqual tagSet.get
  }

  // depend on index text query (FWC - wtf does this comment mean, and why is it relevant?)
//  it should "(UNIT) search fully populated marks by uuid, query and tags" in {
//    val m1Stub = m1.copy(mark = MarkData(m1.mark.subj, m1.mark.url), aux = None)
//    marksDao.search(uuid1, cmt.get, tagSet.get).futureValue shouldEqual Seq(m1Stub)
//  }

  it should "(UNIT) search partially populated marks by uuid, query and tags" in {
    val ms1Stub = MSearchable(
      m1.userId,
      m1.id,
      mark = MDSearchable(m1.mark.subj, m1.mark.url, None),
      timeFrom = m1.timeFrom,
      score = Some(1.0))

    val searchRes = marksDao.search(uuid1, cmt.get, tagSet.get).futureValue.head

    searchRes.userId shouldEqual ms1Stub.userId
    searchRes.id shouldEqual ms1Stub.id
    searchRes.mark.subj shouldEqual ms1Stub.mark.subj
    searchRes.mark.url shouldEqual ms1Stub.mark.url
    searchRes.score shouldEqual ms1Stub.score
  }

  it should "(UNIT) update marks by uuid, id, markData" in {
    marksDao.update(uuid1, m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) delete mark by uuid, id" in {
    marksDao.delete(uuid1, m1.id :: Nil).futureValue shouldEqual 1
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(uuid1, m1.mark.url.get).futureValue shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, both current and not" in {
    marksDao.findMissingReprs(-1).futureValue.filter(_.userId == uuid1).map(_.id) shouldEqual Seq(m1.id, m1.id)
  }

  it should "(UNIT) find marks with missing reprs, and be able to limit the quantity returned" in {
    marksDao.findMissingReprs(1).futureValue.length shouldEqual 1 // there should be a total of 4 ...
    marksDao.findMissingReprs(3).futureValue.length shouldEqual 3 //               ... 2 m1s, m3, and m4
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
