package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models._
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils._
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global // why wasn't this necessary before?

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

  val reprInfoPub = ReprInfo("reprId1", ReprType.PUBLIC, created = TIME_NOW)
  val reprInfoUsr = ReprInfo("reprId2", ReprType.USER_CONTENT, created = TIME_NOW)
  val states = Seq(reprInfoUsr)
  val url = "http://hamstoo.com/as"

  val m1 = Mark(uuid1, "m1id", MarkData("a subject1", Some(url), tags = tagSet, comment = cmt), reprs = states)
  val m2 = Mark( uuid1, "m2id", MarkData("a subject2", Some("http://hamstoo.com"), tags = tagSet))
  val m3 = Mark(uuid2, "m3id", MarkData("a subject3", None))
  val m4 = Mark(uuid2, m3.id, MarkData("a subject4", Some("http://hamstoo.com")), timeThru = INF_TIME - 1)

  val page = Page(m1.userId, m1.id, ReprType.PUBLIC, "asdasd".toCharArray.map(_.toByte))
  val p = Page(m1.userId, m1.id, ReprType.PUBLIC, "content".toCharArray.map(_.toByte))

  "MongoMarksDao" should "(UNIT) insert mark" in {
    marksDao.insert(m1).futureValue shouldEqual m1
  }

  it should "(UNIT) insert page for mark and increase 'nPendingPrivPages' number" in {
    marksDao.insertPage(p).futureValue shouldEqual p
    pagesDao.retrievePages(m1.userId, m1.id, ReprType.PUBLIC).futureValue.head shouldEqual p
    marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.nPendingPrivPages shouldEqual 1
  }

  it should "(UNIT) remove page for mark and increase 'nPendingPrivPages' number" in {
    marksDao.removePage(p).futureValue shouldEqual {}
    pagesDao.retrievePages(m1.userId, m1.id, ReprType.PUBLIC).futureValue.headOption shouldEqual None
    marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.nPendingPrivPages shouldEqual 0
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
    marksDao.saveReprInfo(m1, reprInfoPub).futureValue shouldEqual {}
    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    reprs.size shouldEqual 2
    reprs.exists(_.isPublic) shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, both current and not (update: no longer finding non-current)" in {
    val reprs = marksDao.findMissingReprs(-1).futureValue.map(_.id)
    reprs.size shouldEqual 2
    reprs.contains(m2.id) shouldEqual true
    reprs.contains(m3.id) shouldEqual true
  }

  it should "(UNIT) find marks with missing reprs, and be able to limit the quantity returned" in {
    marksDao.findMissingReprs(1).futureValue.map(_.id) shouldEqual Seq(m2.id)
  }

  it should "(UNIT) find marks with missing expect rating" in {
    val marks = marksDao.findMissingExpectedRatings(-1).futureValue.map(_.id)
    marks.size shouldEqual 1
    marks.contains(m1.id) shouldEqual true
  }

  it should "(UNIT) retrieve by uuid and id" in {
    marksDao.retrieve(User(uuid1), m1.id).futureValue.get.id shouldEqual m1.id
  }

  it should "(UNIT) retrieve by uuid" in {
    marksDao.retrieve(uuid2).futureValue.map(_.id) shouldEqual Seq(m3.id)
  }

  it should "(UNIT) retrieve mark history" in {
    marksDao.insert(m4).futureValue shouldEqual m4
    marksDao.retrieveInsecureHist(m3.id).futureValue.map(_.mark) shouldEqual Seq(m3.mark, m4.mark)
  }

  it should "(UNIT) retrieve by uuid and url" in {
    marksDao.retrieveByUrl(url, uuid1).futureValue.get.id shouldEqual m1.id
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

  it should "(UNIT) perform MongoDB Text Index marks search by user ID, query and tags" in {
    val md1Stub = MarkData(m1.mark.subj, m1.mark.url, tags = m1.mark.tags, comment = m1.mark.comment)
    val m1Stub = m1.copy(mark = md1Stub, aux = m1.aux.map(_.cleanRanges), score = Some(1.0), reprs = Seq(reprInfoUsr, reprInfoPub))
    marksDao.search(Set(uuid1), cmt.get).map(_.filter(_.hasTags(tagSet.get))).futureValue shouldEqual Set(m1Stub)
  }

  it should "(UNIT) find duplicate of mark data, for user, by subject" in {
    marksDao.findDuplicateSubject(m3.userId, m3.mark.subj).futureValue should not equal None
  }

  it should "(UNIT) retrieve represented marks by uuid and tag set" in {
    println(marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs)
    val repred = marksDao.retrieveRepred(m1.userId, tagSet.get).futureValue.map(_.id)
    repred.size shouldEqual 1
    repred.contains(m1.id) shouldEqual true
  }

  it should "(UNIT) update public representation rating id" in {
    val newERat = "NewRatID"
    marksDao.updateExpectedRating(m1, Right(ReprType.PUBLIC), "NewRatID").futureValue shouldEqual {}
    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isPublic)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update users representation rating id" in {
    val newERat = "NewRatID"
    marksDao.updateExpectedRating(m1, Right(ReprType.USER_CONTENT), "NewRatID").futureValue shouldEqual {}
    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val pubRepr = reprs.find(_.isUserContent)
    pubRepr should not equal None
    pubRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) update private representation rating id" in {
    val newERat = "NewRatID"
    val privRepr = ReprInfo("reprId2", ReprType.PRIVATE, created = TIME_NOW)
    marksDao.saveReprInfo(m1, privRepr).futureValue shouldEqual {}

    val reprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    reprs.size shouldEqual 3
    reprs.exists(_.isPrivate) shouldEqual true

    marksDao.updateExpectedRating(m1, Left(privRepr.reprId), newERat).futureValue shouldEqual {}
    val updatedReprs = marksDao.retrieve(User(m1.userId), m1.id).futureValue.get.reprs
    val updatedPrivRepr = updatedReprs.find(_.reprId == privRepr.reprId)
    updatedPrivRepr should not equal None
    updatedPrivRepr.get.expRating.get shouldEqual newERat
  }

  it should "(UNIT) unset public representation info" in {
    marksDao.unsetRepr(m1, Right(ReprType.PUBLIC)).futureValue shouldEqual {}
    marksDao.retrieve(User(uuid1), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isPublic) shouldEqual false
  }

  it should "(UNIT) unset user representation info" in {
    marksDao.unsetRepr(m1, Right(ReprType.USER_CONTENT)).futureValue shouldEqual {}
    marksDao.retrieve(User(uuid1), m1.id, timeFrom = Some(m1.timeFrom)).futureValue.get.reprs.exists(_.isUserContent) shouldEqual false
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
