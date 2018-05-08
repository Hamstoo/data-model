/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils._
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * MongoMarksDao tests.
  */
class MarkDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._

  val uuid1: UUID = constructUserId()
  val uuid2: UUID = constructUserId()

  val tagSet = Some(Set("tag1, tag2"))
  val cmt = Some("Query")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"), tags = tagSet)

  val reprInfoPub = ReprInfo("reprId1", ReprType.PUBLIC)
  val reprInfoUsr = ReprInfo("reprId2", ReprType.USER_CONTENT)
  val reprs = Seq(reprInfoUsr) // intentionally not adding reprInfoPub, which gets added in a test below
  val url = "http://hamstoo.com/as"

  // set pagePending = true as if m1 came from the Chrome extension
  val m1 = Mark(uuid1, "m1id", MarkData("a subject1", Some(url), tags = tagSet, comment = cmt), reprs = reprs)
  val m2 = Mark(uuid1, "m2id", MarkData("a subject2", Some("http://hamstoo.com"), tags = tagSet))
  val m3 = Mark(uuid2, "m3id", MarkData("a subject3", None))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject4", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert page for mark" in {
    val priv = Page(m1.id, ReprType.PRIVATE, "content".toCharArray.map(_.toByte))
    pagesDao.insertPage(priv).futureValue shouldEqual priv
    pagesDao.retrievePages(m1.id, ReprType.PRIVATE).futureValue.head shouldEqual priv // unnecessary call to retrievePages?
  }

  it should "(UNIT) insert streams of marks" in {
    val markStream = m2 #:: m3 #:: Stream.empty[Mark]
    marksDao.insertStream(markStream).futureValue shouldEqual 2
  }

  it should "(UNIT) insert ReprInfo" in {
    marksDao.insertReprInfo(m1.id, reprInfoPub).futureValue shouldEqual {}

    val mark = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value

    val reprs0 = mark.reprs
    reprs0.size shouldEqual 2
    reprs0.map(_.reprId).toSet shouldEqual Set(reprInfoPub.reprId, reprInfoUsr.reprId)
    reprs0.exists(_.isPublic) shouldEqual true

    val newReprID = "newReprId1"
    marksDao.insertReprInfo(m1.id, reprInfoPub.copy(reprId = newReprID)).futureValue shouldEqual {}

    val reprs1 = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs
    reprs1.size shouldEqual 2
    reprs1.map(_.reprId).toSet shouldEqual Set(newReprID, reprInfoUsr.reprId)
    reprs1.exists(_.isPublic) shouldEqual true
  }

  it should "(UNIT) retrieve by userId and markId" in {
    marksDao.retrieve(User(uuid1), m1.id).futureValue.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by userId" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) retrieve by userId and URL" in {
    marksDao.retrieveByUrl(url, uuid1).futureValue.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve same mark by userId and secure URL" in {
    marksDao.retrieveByUrl(url.replaceFirst("http://", "https://"), uuid1).futureValue.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by userId and tags" in {
    val tagged = marksDao.retrieveTagged(uuid1, tagSet.get).futureValue.map(_.id)
    tagged.size shouldEqual 2
    tagged.contains(m1.id) shouldEqual true
    tagged.contains(m2.id) shouldEqual true
  }

  it should "(UNIT) retrieve mark tags by userId" in {
    marksDao.retrieveTags(uuid1).futureValue shouldEqual tagSet.get
  }

  it should "(UNIT) perform MongoDB Text Index marks search by user ID, query and tags" in {
    val md1Stub = MarkData(m1.mark.subj, m1.mark.url, tags = m1.mark.tags, comment = m1.mark.comment)
    val m1Stub = m1.copy(mark = md1Stub,
      aux = m1.aux.map(_.cleanRanges),
      score = Some(1.0), // this field is only populated by `MongoMarksDao.search`
      reprs = Seq(reprInfoUsr, reprInfoPub.copy(reprId = "newReprId1"))
    )
    val set = marksDao.search(Set(uuid1), cmt.get).map(_.filter(_.hasTags(tagSet.get))).futureValue
    set.map(_.xtoString) shouldEqual Set(m1Stub.xtoString)
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    marksDao.findDuplicateSubject(m3.userId, m3.mark.subj).futureValue should not equal None
  }

  it should "(UNIT) retrieve represented marks by userId and tag set" in {
    println(marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs)
    val repred = marksDao.retrieveRepred(m1.userId, tagSet.get).futureValue

    repred.size shouldEqual 1
    repred.head shouldBe a [MSearchable]
    repred.exists(_.id == m1.id) shouldEqual true
  }

  it should "(UNIT) update PUBLIC expected rating ID" in {
    val newERat = "NewRatID"
    Right(ReprType.PUBLIC).toReprId(m1)
      .flatMap(marksDao.updateExpectedRating(m1, _, "NewRatID")).futureValue shouldEqual {}
    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isPublic)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update USER_CONTENT expected rating ID" in {
    val newERat = "NewRatID"
    Right(ReprType.USER_CONTENT).toReprId(m1)
      .flatMap(marksDao.updateExpectedRating(m1, _, "NewRatID")).futureValue shouldEqual {}
    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isUserContent)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update PRIVATE expected rating ID" in {
    val newERat = "NewRatID"
    val privRepr = ReprInfo("reprId2", ReprType.PRIVATE)
    marksDao.insertReprInfo(m1.id, privRepr).futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    reprs.size shouldEqual 3
    reprs.exists(_.isPrivate) shouldEqual true

    marksDao.updateExpectedRating(m1, privRepr.reprId, newERat).futureValue shouldEqual {}
    val updatedReprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val updatedPrivRepr = updatedReprs.find(_.reprId == privRepr.reprId)
    updatedPrivRepr should not equal None
    updatedPrivRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) unset PUBLIC ReprInfo" in {
    marksDao.unsetRepr(m1, Right(ReprType.PUBLIC)).futureValue shouldEqual {}
    marksDao.retrieve(User(uuid1), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isPublic) shouldEqual false
  }

  it should "(UNIT) unset USER_CONTENT ReprInfo" in {
    marksDao.unsetRepr(m1, Right(ReprType.USER_CONTENT)).futureValue shouldEqual {}
    marksDao.retrieve(User(uuid1), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isUserContent) shouldEqual false
  }

  it should "(UNIT) update MarkData by userId and markId" in {
    marksDao.update(User(m1.userId), m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) delete mark by userId and markId" in {
    marksDao.delete(uuid1, m1.id :: Nil).futureValue shouldEqual 1
    marksDao.retrieve(User(m1.userId), m1.id).futureValue shouldEqual None
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(uuid1, m1.mark.url.get).futureValue shouldEqual true
  }
}
