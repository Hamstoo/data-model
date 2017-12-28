package com.hamstoo.models

import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.TIME_NOW
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Shareable trait and UserGroup model unit tests
  */
class ShareableTests extends FlatSpecWithMatchers
    with MongoEnvironment with MockitoSugar with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  val sharer: User = userA
  val sharee: User = userB
  val somree = Some(sharee)

  val ugUserIds = UserGroup(Some("ugUserIds"), userIds = Some(Set(sharee.id)))
  val ugEmails = UserGroup(Some("ugEmails"), emails = sharee.profiles.head.email.map(Set(_)))

  override def beforeAll(): Unit = {
    super.beforeAll()
    userDao.saveGroup(ugUserIds).futureValue
    userDao.saveGroup(ugEmails).futureValue
  }

  val md = MarkData("subj", None)
  val mNotShared = Mark(sharer.id, mark = md)
  val mUserRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(ugUserIds.id, None)))
  val mEmailRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, ugEmails.id)))
  val mPublicRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(Some(UserGroup.PUBLIC_ID), None)))
  val mLoggedInRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, Some(UserGroup.LOGGED_IN_ID))))

  "Shareable" should "(UNIT) authorize reading" in {
    mEmailRW.isAuthorizedRead(Some(sharer)).futureValue shouldBe true // users should be able to read their own marks
    mUserRead.isAuthorizedRead(somree).futureValue shouldBe true
    mEmailRW.isAuthorizedRead(somree).futureValue shouldBe true
    mPublicRead.isAuthorizedRead(None).futureValue shouldBe true // user doesn't even have to be logged in
  }

  it should "(UNIT) authorize writing" in {
    mUserRead.isAuthorizedWrite(Some(sharer)).futureValue shouldBe true // users should be able to write their own marks
    mUserRead.isAuthorizedWrite(Some(sharer)).futureValue shouldBe true
    mEmailRW.isAuthorizedWrite(somree).futureValue shouldBe true
    mLoggedInRW.isAuthorizedWrite(somree).futureValue shouldBe true
  }

  /*it should "(UNIT) authorize sharing" in {
    mNotShared.isAuthorizedShare(sharer).futureValue shouldBe true // owner can choose to share with anyone
    mUserRead.isAuthorizedShare(sharer).futureValue shouldBe true
    mPublicRead.isAuthorizedShare(sharee).futureValue shouldBe true
  }*/

  it should "(UNIT) prevent reading" in {
    mNotShared.isAuthorizedRead(somree).futureValue shouldBe false
    mLoggedInRW.isAuthorizedRead(None).futureValue shouldBe false // login required
  }

  it should "(UNIT) prevent writing" in {
    mNotShared.isAuthorizedWrite(somree).futureValue shouldBe false
    mUserRead.isAuthorizedWrite(somree).futureValue shouldBe false
    mPublicRead.isAuthorizedWrite(somree).futureValue shouldBe false // readOnly = true
    mPublicRead.isAuthorizedWrite(None).futureValue shouldBe false
    mLoggedInRW.isAuthorizedWrite(None).futureValue shouldBe false // login required
  }

  /*it should "(UNIT) prevent sharing" in {
    mNotShared.isAuthorizedShare(sharee).futureValue shouldBe false
    mEmailRW.isAuthorizedShare(sharee).futureValue shouldBe false // only public can be shared by sharee
  }*/

  "SharedWith" should "(UNIT) be convertable to a list of email addresses" in {
    val ugSharer = UserGroup(Some("ugSharer"), userIds = Some(Set(sharer.id)))
    userDao.save(sharer).futureValue
    userDao.saveGroup(ugSharer, Some(UserGroup.SharedObj("someMarkId", TIME_NOW))).futureValue
    val emails = Set(sharer, sharee).map(_.profiles.head.email.get)
    SharedWith(ugSharer.id, ugEmails.id).emails.futureValue shouldBe emails
  }

  "ExtendedOptionUserGroup" should "(UNIT) act like a set" in {
    import UserGroup.ExtendedOptionUserGroup

    val userGroups: Map[String, Option[UserGroup]] = Map(
      "ugUserIds" -> Some(ugUserIds),
      "ugEmails" -> Some(ugEmails),
      "twoUserIds" -> Some(UserGroup(Some("twoUserIds"), userIds = ugUserIds.userIds.map(_ + constructUserId()))),
      "twoEmails" -> Some(UserGroup(Some("twoEmails"), emails = ugUserIds.emails.map(_ + "c@mail"))),
      "none" -> None) ++
      UserGroup.PUBLIC_USER_GROUPS.mapValues(Some(_))

    for((k0, v0) <- userGroups; (k1, v1) <- userGroups) {
      (v1 - v0).union(v0 - v1).union(v0 intersect v1).map(_.copy(id = None)) shouldBe v1.union(v0).map(_.copy(id = None))
    }
  }
}
