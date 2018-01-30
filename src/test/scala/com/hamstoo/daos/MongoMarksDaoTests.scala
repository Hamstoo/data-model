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

  val msPub = ReprInfo("reprId1", Representation.PUBLIC, created = TIME_NOW)
  val msUser = ReprInfo("reprId2", Representation.USERS, created = TIME_NOW)
  val states = Seq(msUser)
  val url = "http://hamstoo.com/as"

  val m1 = Mark(
    uuid1,
    "m1id",
    MarkData("a subject1", Some(url), tags = tagSet, comment = cmt),
    reprs = states)

  val m2 = Mark(
    uuid1,
    "m2id",
    MarkData("a subject2", Some("http://hamstoo.com"), tags = tagSet))

  val m3 = Mark(uuid2, "m3id", MarkData("a subject3", None))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject4", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

  val page = Page(m1.userId, m1.id, Representation.PUBLIC, "asdasd".toCharArray.map(_.toByte))
  val p = Page(m1.userId, m1.id, Representation.PUBLIC, "content".toCharArray.map(_.toByte))

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert page for mark and increase 'pndPrivPages' number" in {
    marksDao.insertPage(p).futureValue shouldEqual p

    pagesDao.retrievePublicPage(m1.userId, m1.id).futureValue.value shouldEqual p

    marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.pndPrivPages shouldEqual 1
  }

  it should "(UNIT) remove page for mark and increase 'pndPrivPages' number" in {
    marksDao.removePage(p).futureValue shouldEqual {}

    pagesDao.retrievePublicPage(m1.userId, m1.id).futureValue shouldEqual None

    marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.pndPrivPages shouldEqual 0
  }

  it should "(UNIT) insert stream of mark" in {
    val markStream = m2 #:: m3 #:: Stream.empty[Mark]
    marksDao.insertStream(markStream).futureValue shouldEqual 2
  }

  it should "(UNIT) find marks with missing public reprs" in {
    val noPubReprs = marksDao.findMissingPublicReprs(-1).futureValue

    noPubReprs.size shouldEqual 3

    noPubReprs.exists(_.id == m1.id) shouldEqual true
  }

  it should "(UNIT) save representation info" in {
    marksDao.saveReprInfo(m1.userId, m1.id, msPub).futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs

    reprs.size shouldEqual 2
    reprs.exists(_.reprType == Representation.PUBLIC) shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, both current and not (update: no longer finding non-current)" in {
    val reprs = marksDao.findMissingReprs(-1).futureValue.map(_.id)

    reprs.size shouldEqual 3

    reprs.contains(m2.id) shouldEqual true

    reprs.contains(m3.id) shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, and be able to limit the quantity returned" in {
    marksDao.findMissingReprs(1).futureValue.map(_.id) shouldEqual Seq(m1.id)
  }

  it should "(UNIT) find marks with missing expect rating" in {
    val marks = marksDao.findMissingExpectedRatings(-1).futureValue.map(_.id)

    marks.size shouldEqual 1

    marks.contains(m1.id) shouldEqual true
  }

  it should "(UNIT) retrieve by uuid and id" in {
    marksDao.retrieve(User(uuid1), m1.id).futureValue.value.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by uuid" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) retrieve mark history" in {
    marksDao.insert(m4).futureValue shouldEqual m4
    marksDao.retrieveInsecureHist(m3.id).futureValue.map(_.mark) shouldEqual Seq(m3.mark, m4.mark)
  }

  it should "(UNIT) retrieve by uuid and url" in {
    marksDao.retrieveByUrl(url, uuid1).futureValue.value.id shouldEqual m1.id
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
    marksDao.search(uuid1, cmt.get, tagSet.get).futureValue.map(_.id) shouldEqual Seq(m1.id)
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    marksDao.findDuplicateSubject(m3.userId, m3.mark.subj).futureValue should not equal None
  }

  it should "(UNIT) retrieve represented marks by uuid and tag set" in {
    println(marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs)
    val repred = marksDao.retrieveRepred(m1.userId, tagSet.get).futureValue.map(_.id)

    repred.size shouldEqual 1
    repred.contains(m1.id) shouldEqual true
  }

  it should "(UNIT) update public representation rating id" in {
    val newERat = "NewRatID"
    marksDao.updatePublicERatingId(m1.userId, m1.id, m1.timeFrom, "NewRatID").futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs

    val pubRepr = reprs.find(_.reprType == Representation.PUBLIC)

    pubRepr should not equal None

    pubRepr.value.expRating.value shouldEqual newERat
  }

  it should "(UNIT) update users representation rating id" in {
    val newERat = "NewRatID"
    marksDao.updateUsersERatingId(m1.userId, m1.id, m1.timeFrom, "NewRatID").futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs

    val pubRepr = reprs.find(_.reprType == Representation.USERS)

    pubRepr should not equal None

    pubRepr.value.expRating.value shouldEqual newERat
  }

  it should "(UNIT) update private representation rating id" in {
    val newERat = "NewRatID"
    val privRepr = ReprInfo("reprId2", Representation.PRIVATE, created = TIME_NOW)

    marksDao.saveReprInfo(m1.userId, m1.id, privRepr).futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs

    reprs.size shouldEqual 3
    reprs.exists(_.reprType == Representation.PRIVATE) shouldEqual true

    marksDao.updatePrivateERatingId(m1.userId, m1.id, privRepr.reprId, m1.timeFrom, newERat).futureValue shouldEqual {}


    val updatedReprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.value.reprs

    val updatedPrivRepr = updatedReprs.find(_.reprId == privRepr.reprId)

    updatedPrivRepr should not equal None

    updatedPrivRepr.value.expRating.value shouldEqual newERat
  }

  it should "(UNIT) unset public representation info" in {
    marksDao.unsetPublicRepr(uuid1, m1.id).futureValue shouldEqual {}

    marksDao.retrieve(User(uuid1), m1.id)
      .futureValue
      .value
      .reprs
      .exists(_.reprType == Representation.PUBLIC) shouldEqual false
  }

  it should "(UNIT) unset user representation info" in {
    marksDao.unsetUserRepr(uuid1, m1.id).futureValue shouldEqual {}

    marksDao.retrieve(User(uuid1), m1.id)
      .futureValue
      .value
      .reprs
      .exists(_.reprType == Representation.USERS) shouldEqual false
  }

  it should "(UNIT) update marks by uuid, id, markData" in {
    marksDao.update(User(m1.userId), m1.id, newMarkData).futureValue.mark shouldEqual newMarkData
  }

  it should "(UNIT) delete mark by uuid, id" in {
    marksDao.delete(uuid1, m1.id :: Nil).futureValue shouldEqual 1

    marksDao.retrieve(User(m1.userId), m1.id).futureValue shouldEqual None
  }

  it should "(UNIT) check if mark was every previously deleted" in {
    marksDao.isDeleted(uuid1, m1.mark.url.get).futureValue shouldEqual true
  }
}
