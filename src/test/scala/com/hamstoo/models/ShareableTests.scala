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

  val ugUserIds = UserGroup("ugUserIds", userIds = Some(Set(sharee.id)))
  val ugEmails = UserGroup("ugEmails", emails = sharee.profiles.head.email.map(Set(_)))

  override def beforeAll(): Unit = {
    super.beforeAll()
    userDao.saveGroup(ugUserIds).futureValue
    userDao.saveGroup(ugEmails).futureValue
  }

  val sgUserIds = ShareGroup(SharedWith.Level.LISTED.id, Some(ugUserIds.id))
  val sgEmails = ShareGroup(SharedWith.Level.LISTED.id, Some(ugEmails.id))
  val sgPublic = ShareGroup(SharedWith.Level.PUBLIC.id, None)
  val sgLoggedIn = ShareGroup(SharedWith.Level.LOGGED_IN.id, None)

  val md = MarkData("subj", None)
  val mNotShared = Mark(sharer.id, mark = md)
  val mUserRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(Some(sgUserIds), None)))
  val mEmailRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, Some(sgEmails))))
  val mPublicRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(Some(sgPublic), None)))
  val mLoggedInRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, Some(sgLoggedIn))))

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

  it should "(UNIT) authorize sharing" in {
    mNotShared.isAuthorizedShare(sharer) shouldBe true // owner can choose to share with anyone
    mUserRead.isAuthorizedShare(sharer) shouldBe true
    mPublicRead.isAuthorizedShare(sharee) shouldBe true
  }

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

  it should "(UNIT) prevent sharing" in {
    mNotShared.isAuthorizedShare(sharee) shouldBe false
    mEmailRW.isAuthorizedShare(sharee) shouldBe false // only public can be shared by sharee
  }

  "SharedWith" should "(UNIT) be convertable to a list of email addresses" in {
    val ugSharer = UserGroup("ugSharer", userIds = Some(Set(sharer.id)))
    val sgSharer = ShareGroup(SharedWith.Level.LISTED.id, Some(ugSharer.id))
    userDao.save(sharer).futureValue
    userDao.saveGroup(ugSharer, Some(UserGroup.SharedObj("someMarkId", TIME_NOW))).futureValue
    val emails = Set(sharer, sharee).map(_.profiles.head.email.get)
    SharedWith(Some(sgSharer), Some(sgEmails)).emails.futureValue shouldBe emails
  }

  /*"ExtendedOptionUserGroup" should "(UNIT) act like a set" in {
    import UserGroup.ExtendedOptionUserGroup

    val userGroups: Map[String, Option[UserGroup]] = Map(
      "ugUserIds" -> Some(ugUserIds),
      "ugEmails" -> Some(ugEmails),
      "twoUserIds" -> Some(UserGroup("twoUserIds", userIds = ugUserIds.userIds.map(_ + constructUserId()))),
      "twoEmails" -> Some(UserGroup("twoEmails", emails = ugUserIds.emails.map(_ + "c@mail"))),
      "none" -> None) ++
      UserGroup.PUBLIC_USER_GROUPS.mapValues(Some(_))

    for((k0, v0) <- userGroups; (k1, v1) <- userGroups) {
      (v1 - v0).union(v0 - v1).union(v0 intersect v1).map(_.copy(id = "")) shouldBe v1.union(v0).map(_.copy(id = ""))
    }
  }*/
}
