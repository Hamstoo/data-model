/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.MarkData.SHARED_WITH_ME_TAG
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.SharedWith.Level
import com.hamstoo.models.UserGroup.SharedObj
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

  val userA: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
  val userB: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

  val tagSet = Some(Set("tag1, tag2"))
  val cmt = Some("Query")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"), tags = tagSet)

  val reprInfoPub = ReprInfo("reprId1", ReprType.PUBLIC)
  val reprInfoUsr = ReprInfo("reprId2", ReprType.USER_CONTENT)
  val reprs = Seq(reprInfoUsr) // intentionally not adding reprInfoPub, which gets added in a test below
  val url = "http://hamstoo.com/as"

  // set pagePending = true as if m1 came from the Chrome extension
  val m1 = Mark(userA, "m1id", MarkData("a subject1", Some(url), tags = tagSet, comment = cmt), reprs = reprs)
  val m2 = Mark(userA, "m2id", MarkData("a subject2", Some("http://hamstoo.com"), tags = tagSet))
  val m3 = Mark(userB, "m3id", MarkData("a subject3", None))
  val m4 = Mark(userB, m3.id, MarkData("a subject4", m2.mark.url), timeThru = INF_TIME - 1)

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

    val mark = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.value

    val reprs0 = mark.reprs
    reprs0.size shouldEqual 2
    reprs0.map(_.reprId).toSet shouldEqual Set(reprInfoPub.reprId, reprInfoUsr.reprId)
    reprs0.exists(_.isPublic) shouldEqual true

    val newReprID = "newReprId1"
    marksDao.insertReprInfo(m1.id, reprInfoPub.copy(reprId = newReprID)).futureValue shouldEqual {}

    val reprs1 = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.value.reprs
    reprs1.size shouldEqual 2
    reprs1.map(_.reprId).toSet shouldEqual Set(newReprID, reprInfoUsr.reprId)
    reprs1.exists(_.isPublic) shouldEqual true
  }

  it should "(UNIT) retrieve by userId and markId" in {
    marksDao.retrieveById(User(userA), m1.id).futureValue.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by userId" in {
    marksDao.retrieve(userB).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) retrieve by userId and URL" in {
    marksDao.retrieveByUrl(url, userA).futureValue._1.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve same mark by userId and secure URL" in {
    marksDao.retrieveByUrl(url.replaceFirst("http://", "https://"), userA).futureValue._1.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by userId and tags" in {
    val tagged = marksDao.retrieveTagged(userA, tagSet.get).futureValue.map(_.id)
    tagged.size shouldEqual 2
    tagged.contains(m1.id) shouldEqual true
    tagged.contains(m2.id) shouldEqual true
  }

  it should "(UNIT) retrieve mark tags by userId" in {
    marksDao.retrieveTags(userA).futureValue shouldEqual tagSet.get
  }

  it should "(UNIT) perform MongoDB Text Index marks search by user ID, query and tags" in {
    val md1Stub = MarkData(m1.mark.subj, m1.mark.url, tags = m1.mark.tags, comment = m1.mark.comment)
    val m1Stub = m1.copy(mark = md1Stub,
      aux = m1.aux.map(_.cleanRanges),
      score = Some(1.0), // this field is only populated by `MongoMarksDao.search`
      reprs = Seq(reprInfoUsr, reprInfoPub.copy(reprId = "newReprId1"))
    )
    val set = marksDao.search(Set(userA), cmt.get).map(_.filter(_.hasTags(tagSet.get))).futureValue
    set shouldEqual Set(m1Stub)
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    marksDao.findDuplicateSubject(m3.userId, m3.mark.subj).futureValue should not equal None
  }

  it should "(UNIT) retrieve represented marks by userId and tag set" in {
    println(marksDao.retrieveById(User(m1.userId), m1.id).futureValue.get.reprs)

    // TODO: is this the only place where we specify `requireRepr = true` anymore?
    val repred = marksDao.retrieve(m1.userId, tagSet.get, requireRepr = true).futureValue

    repred.size shouldEqual 1
    repred.head shouldBe a [Mark]
    repred.exists(_.id == m1.id) shouldEqual true
  }

  it should "(UNIT) update PUBLIC expected rating ID" in {
    val newERat = "NewRatID"
    Right(ReprType.PUBLIC).toReprId(m1)
      .flatMap(marksDao.updateExpectedRating(m1, _, "NewRatID")).futureValue shouldEqual {}
    val reprs = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isPublic)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update USER_CONTENT expected rating ID" in {
    val newERat = "NewRatID"
    Right(ReprType.USER_CONTENT).toReprId(m1)
      .flatMap(marksDao.updateExpectedRating(m1, _, "NewRatID")).futureValue shouldEqual {}
    val reprs = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isUserContent)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update PRIVATE expected rating ID" in {
    val newERat = "NewRatID"
    val privRepr = ReprInfo("reprId2", ReprType.PRIVATE)
    marksDao.insertReprInfo(m1.id, privRepr).futureValue shouldEqual {}

    val reprs = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.get.reprs
    reprs.size shouldEqual 3
    reprs.exists(_.isPrivate) shouldEqual true

    marksDao.updateExpectedRating(m1, privRepr.reprId, newERat).futureValue shouldEqual {}
    val updatedReprs = marksDao.retrieveById(User(m1.userId), m1.id).futureValue.get.reprs
    val updatedPrivRepr = updatedReprs.find(_.reprId == privRepr.reprId)
    updatedPrivRepr should not equal None
    updatedPrivRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) unset PUBLIC ReprInfo" in {
    marksDao.unsetRepr(m1, Right(ReprType.PUBLIC)).futureValue shouldEqual {}
    marksDao.retrieveById(User(userA), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isPublic) shouldEqual false
  }

  it should "(UNIT) unset USER_CONTENT ReprInfo" in {
    marksDao.unsetRepr(m1, Right(ReprType.USER_CONTENT)).futureValue shouldEqual {}
    marksDao.retrieveById(User(userA), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isUserContent) shouldEqual false
  }

  it should "(UNIT) update MarkData by userId and markId" in {
    marksDao.update(User(m1.userId), m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) update visits" in {
    marksDao.updateVisits(m1.id, Some(true)).futureValue
    marksDao.updateVisits(m1.id, Some(false)).futureValue
    marksDao.updateVisits(m1.id, None).futureValue
    val updatedAux = marksDao.retrieveInsecure(m1.id).futureValue.get.aux.get
    updatedAux.nOwnerVisits.get shouldEqual 1
    updatedAux.nShareeVisits.get shouldEqual 1
    updatedAux.nUnauthVisits.get shouldEqual 1

    marksDao.updateVisits(m1.id, Some(true)).futureValue
    marksDao.retrieveInsecure(m1.id).futureValue.get.aux.get.nOwnerVisits.get shouldEqual 2
  }

  it should "(UNIT) delete mark by userId and markId" in {
    marksDao.delete(userA, m1.id :: Nil).futureValue shouldEqual 1
    marksDao.retrieveById(User(m1.userId), m1.id).futureValue shouldEqual None
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(userA, m1.mark.url.get).futureValue shouldEqual true
  }

  // see ShareableTests.scala for more tests of sharing functionality
  it should "(UNIT) updateSharedWith" in {
    val ug = UserGroup("updateSharedWith", userIds = Some(Set(userB)), sharedObjs = Seq(SharedObj(m2.id, TIME_NOW)))
    val m2New = marksDao.updateSharedWith(m2, 1, (Level.LISTED, Some(ug)), (Level.PRIVATE, None)).futureValue
    m2.isAuthorizedRead(User(userB)).futureValue shouldBe false
    m2New.isAuthorizedRead(User(userB)).futureValue shouldBe true
    m2New.isAuthorizedWrite(User(userB)).futureValue shouldBe false
  }

  it should "(UNIT) findOrCreateMarkRef and mask via retrieve" in {
    val masked = marksDao.retrieveById(User(userB), m2.id).map(_.get).futureValue
    masked.mark.url shouldEqual m2.mark.url
    masked.id shouldEqual m2.id
    masked.mark.tags.get should contain(SHARED_WITH_ME_TAG)
  }

  it should "(UNIT) retrieve MarkRefs by URL" in {
    val m = marksDao.retrieveByUrl(m2.mark.url.get, userA).map(_._1.get).futureValue
    val mRef = marksDao.retrieveByUrl(m2.mark.url.get, userB).map(_._2.get).futureValue
    m.isRef shouldEqual false
    mRef.isRef shouldEqual true
    Some(m.id) shouldEqual mRef.markRef.map(_.markId)
    m.mark.url shouldEqual mRef.mark.url
  }

  it should "(UNIT) updateMarkRef via update" in {
    marksDao.update(User(userB), m2.id, MarkData("", None, rating = Some(3))).futureValue
    marksDao.update(User(userB), m2.id, MarkData("", None, tags = tagSet.map(_ + "updateMarkRefTAG"))).futureValue
    val masked = marksDao.retrieveById(User(userB), m2.id).map(_.get).futureValue
    masked.mark.url shouldEqual m2.mark.url
    masked.id shouldEqual m2.id
    masked.mark.tags.get should contain("updateMarkRefTAG")
    masked.mark.rating.get shouldEqual 3.0
  }
}
