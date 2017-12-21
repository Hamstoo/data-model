package com.hamstoo.models

import com.hamstoo.models.UserGroup._
import com.hamstoo.test.FlatSpecWithMatchers

/**
  * Shareable trait and UserGroup model unit tests
  */
class ShareableTests extends FlatSpecWithMatchers {

  import com.hamstoo.utils.DataInfo._

  val sharer: User = userA
  val sharee: User = userB
  val somree = Some(sharee)

  val userRead = UserGroup(constructMarkId(), userIds = Some(Set(sharee.id)))
  val emailRW = UserGroup(constructMarkId(), readOnly = false, emails = sharee.profiles.head.email.map(Set(_)))

  val md = MarkData("subj", None)
  val mNotShared = Mark(sharer.id, mark = md)
  val mUserRead = Mark(sharer.id, mark = md, sharedWith = Some(Set(userRead)))
  val mEmailRW = Mark(sharer.id, mark = md, sharedWith = Some(Set(emailRW)))
  val mPublicRead = Mark(sharer.id, mark = md, sharedWith = Some(Set(PUBLIC_READ)))
  val mLoggedInRW = Mark(sharer.id, mark = md, sharedWith = Some(Set(LOGGED_IN_RW)))

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
}
