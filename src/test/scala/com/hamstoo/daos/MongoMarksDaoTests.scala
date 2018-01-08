package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models._
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils._
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
  val pubRepr = Some("PublicRepr")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"), tags = tagSet)

  val stateId = "stateId"

  val newPrivReprId = "someReprID"
  val newPrivExpRating = "NewERId"

  val newPubReprId = "newPubRerpId"
  val newPubReprExpRating = "newPubExpId"

  val ms = MarkState(stateId, "reprId", created = TIME_NOW)
  val states = Seq(ms)
  val updatedStates = Seq(ms.copy(reprId = newPrivReprId, expRating = Some(newPrivExpRating)))

  val url = "http://hamstoo.com/as"
  val page = Page("asdasd".toCharArray.map(_.toByte))

  val m1 = Mark(
    uuid1,
    "m1id",
    MarkData("a subject1", Some("http://hamstoo.com/as"), tags = tagSet, comment = cmt),
    privReprExpRating = states)

  val updateM1: Mark = m1.copy(privReprExpRating = updatedStates)

  val m2 = Mark(
    uuid1,
    "m2id",
    MarkData("a subject2", Some("http://hamstoo.com"), tags = tagSet),
    pubRepr = pubRepr,
    userRepr = Some("UserRepr"))

  val m3 = Mark(uuid2, "m3id", MarkData("a subject3", None))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject4", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert stream of mark" in {
    val markStream = m2 #:: m3 #:: Stream.empty[Mark]
    marksDao.insertStream(markStream).futureValue shouldEqual 2
  }

  it should "(UNIT) add page source" in {
    marksDao.addPageSource(m1.userId, m1.id, page, ensureNoPrivRepr = false).futureValue shouldEqual {}

    marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.pages shouldEqual Seq(page)
  }

  it should "(UNIT) find marks with missing reprs, both current and not (update: no longer finding non-current)" in {
    val missReprs = marksDao.findMissingReprs(-1).futureValue.map(_.id)

    missReprs.size shouldEqual 2

    missReprs.contains(m1.id) shouldEqual true

    missReprs.contains(m3.id) shouldEqual true

  }

  it should "(UNIT) find marks with missing reprs, and be able to limit the quantity returned" in {
    marksDao.findMissingReprs(1).futureValue.map(_.id) shouldEqual Seq(m1.id)
  }

  it should "(UNIT) update private representation id" in {
    marksDao.updatePrivateReprId(m1.userId, m1.id, stateId, newPrivReprId, m1.timeFrom, Some(page)).futureValue shouldEqual {}

    val retrievedMark = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value

    retrievedMark.privReprExpRating.exists(_.reprId == newPrivReprId) shouldEqual true

    retrievedMark.pages shouldEqual Nil
  }

  it should "(UNIT) find marks with missing expect rating" in {
    val marks = marksDao.findMissingExpectedRatings(-1).futureValue.map(_.id)

    marks.size shouldEqual 2

    marks.contains(m1.id)

    marks.contains(m2.id)
  }

  it should "(UNIT) update private expect rating" in {

    marksDao.updatePrivateERatingId(m1.userId, m1.id, stateId, newPrivExpRating, m1.timeFrom).futureValue shouldEqual {}

    val m = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value

    m.privReprExpRating.exists(_.expRating.contains(newPrivExpRating)) shouldEqual true

  }

  it should "(UNIT) retrieve by uuid and id" in {
    marksDao.retrieve(User(uuid1), m1.id).futureValue.value shouldEqual updateM1
  }

  it should "(UNIT) retrieve by uuid" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) retrieve mark history" in {
    marksDao.insert(m4).futureValue shouldEqual m4
    marksDao.retrieveInsecureHist(m3.id).futureValue.map(_.mark) shouldEqual Seq(m3.mark, m4.mark)
  }

  it should "(UNIT) retrieve by uuid and url" in {
    marksDao.retrieveByUrl(url, uuid1).futureValue.value shouldEqual updateM1
  }

  it should "(UNIT) retrieve by uuid and tags" in {
    val tagged = marksDao.retrieveTagged(uuid1, tagSet.get).futureValue.map(_.id)

    tagged.size shouldEqual 2

    tagged.contains(m1.id) shouldEqual true

    tagged.contains(m2.id) shouldEqual true
  }

  it should "(UNIT) retrieve mark tags by uuid" in {
    marksDao.retrieveTags(uuid1).futureValue shouldEqual tagSet.get
  }

  it should "(UNIT) search marks by uuid, query and tags" in {
    marksDao.search(uuid1, cmt.get, tagSet.get).futureValue shouldEqual Seq(updateM1)
  }

  it should "(UNIT) update marks by uuid, id, markData" in {
    marksDao.update(User(uuid1), m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    marksDao.findDuplicateSubject(m3.userId, m3.mark.subj).futureValue should not equal None
  }

  it should "(UNIT) retrieve represented marks by uuid and tag set" in {
    val repred = marksDao.retrieveRepred(m1.userId, tagSet.get).futureValue.map(_.id)

    repred.size shouldEqual 2
    repred.contains(m1.id) shouldEqual true
    repred.contains(m2.id) shouldEqual true
  }

  it should "update public representation id" in {

    val timeFrom = marksDao.retrieve(User(m2.userId), m2.id).futureValue.value.timeFrom

    marksDao.updatePublicReprId(m2.userId, m2.id, timeFrom, newPubReprId).futureValue shouldEqual {}

    marksDao.retrieve(User(m2.userId), m2.id).futureValue.value.pubRepr.value shouldEqual newPubReprId
  }

  it should "update public representation expect rating" in {

    val timeFrom = marksDao.retrieve(User(m2.userId), m2.id).futureValue.value.timeFrom

    marksDao.updatePublicERatingId(m2.userId, m2.id, timeFrom, newPubReprExpRating).futureValue shouldEqual {}

    marksDao.retrieve(User(m2.userId), m2.id).futureValue.value.pubExpRating.value shouldEqual newPubReprExpRating
  }

  it should "(UNIT) delete mark by uuid, id" in {
    marksDao.delete(uuid1, m1.id :: Nil).futureValue shouldEqual 1

    marksDao.retrieve(User(m1.userId), m1.id).futureValue shouldEqual None
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(uuid1, m1.mark.url.get).futureValue shouldEqual true
  }
}
