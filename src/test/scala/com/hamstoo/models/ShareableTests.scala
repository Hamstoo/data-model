package com.hamstoo.models

import java.util.UUID

import com.hamstoo.daos.MongoUserDao
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Shareable trait and UserGroup model unit tests
  */
class ShareableTests extends FlatSpecWithMatchers with MockitoSugar with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  val sharer: User = userA
  val sharee: User = userB
  val somree = Some(sharee)

  val withUserIds = Some(UserGroup(constructMarkId(), userIds = Some(Set(sharee.id))))
  val withEmails = Some(UserGroup(constructMarkId(), emails = sharee.profiles.head.email.map(Set(_))))

  val md = MarkData("subj", None)
  val mNotShared = Mark(sharer.id, mark = md)
  val mUserRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(withUserIds, None)))
  val mEmailRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, withEmails)))
  val mPublicRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(Some(UserGroup.PUBLIC), None)))
  val mLoggedInRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, Some(UserGroup.LOGGED_IN))))

  "Shareable" should "(UNIT) authorize reading" in {
    mEmailRW.isAuthorizedRead(Some(sharer)) shouldBe true // users should be able to read their own marks
    mUserRead.isAuthorizedRead(somree) shouldBe true
    mEmailRW.isAuthorizedRead(somree) shouldBe true
    mPublicRead.isAuthorizedRead(None) shouldBe true // user doesn't even have to be logged in
  }

  it should "(UNIT) authorize writing" in {
    mUserRead.isAuthorizedWrite(Some(sharer)) shouldBe true // users should be able to write their own marks
    mUserRead.isAuthorizedWrite(Some(sharer)) shouldBe true
    mEmailRW.isAuthorizedWrite(somree) shouldBe true
    mLoggedInRW.isAuthorizedWrite(somree) shouldBe true
  }

  /*it should "(UNIT) authorize sharing" in {
    mNotShared.isAuthorizedShare(sharer) shouldBe true // owner can choose to share with anyone
    mUserRead.isAuthorizedShare(sharer) shouldBe true
    mPublicRead.isAuthorizedShare(sharee) shouldBe true
  }*/

  it should "(UNIT) prevent reading" in {
    mNotShared.isAuthorizedRead(somree) shouldBe false
    mLoggedInRW.isAuthorizedRead(None) shouldBe false // login required
  }

  it should "(UNIT) prevent writing" in {
    mNotShared.isAuthorizedWrite(somree) shouldBe false
    mUserRead.isAuthorizedWrite(somree) shouldBe false
    mPublicRead.isAuthorizedWrite(somree) shouldBe false // readOnly = true
    mPublicRead.isAuthorizedWrite(None) shouldBe false
    mLoggedInRW.isAuthorizedWrite(None) shouldBe false // login required
  }

  /*it should "(UNIT) prevent sharing" in {
    mNotShared.isAuthorizedShare(sharee) shouldBe false
    mEmailRW.isAuthorizedShare(sharee) shouldBe false // only public can be shared by sharee
  }*/

  "SharedWith" should "(UNIT) be convertable to a list of email addresses" in {
    val sharerUserGroup = Some(UserGroup(constructMarkId(), userIds = Some(Set(sharer.id))))
    val userDao: MongoUserDao = mock[MongoUserDao]
    when(userDao.retrieve(any[UUID])).thenReturn(Future.successful(Some(sharer)))
    val emails = Set(sharer, sharee).map(_.profiles.head.email.get)
    SharedWith(sharerUserGroup, withEmails).emails(userDao).futureValue shouldBe emails
  }

  "ExtendedOptionUserGroup" should "(UNIT) act like a set" in {
    import UserGroup.ExtendedOptionUserGroup

    val userGroups: Map[String, Option[UserGroup]] = Map(
      "withUserIds1" -> withUserIds,
      "withEmails1" -> withEmails,
      "withUserIds2" -> Some(UserGroup(userIds = withUserIds.get.userIds.map(_ + constructUserId()))),
      "withEmails2" -> Some(UserGroup(emails = withEmails.get.emails.map(_ + "c@mail"))),
      "none" -> None) ++
      UserGroup.PUBLIC_USER_GROUPS.mapValues(Some(_))

    for((k0, v0) <- userGroups; (k1, v1) <- userGroups) {
      (v1 - v0).union(v0 - v1).union(v0 intersect v1).map(_.copy(id = "")) shouldBe v1.union(v0).map(_.copy(id = ""))
    }
  }
}
