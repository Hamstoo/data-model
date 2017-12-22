package com.hamstoo.models

import com.hamstoo.test.FlatSpecWithMatchers

/**
  * Shareable trait and UserGroup model unit tests
  */
class ShareableTests extends FlatSpecWithMatchers {

  import com.hamstoo.utils.DataInfo._

  val sharer: User = userA
  val sharee: User = userB
  val somree = Some(sharee)

  val userRead = Some(UserGroup(constructMarkId(), userIds = Some(Set(sharee.id))))
  val emailRW = Some(UserGroup(constructMarkId(), emails = sharee.profiles.head.email.map(Set(_))))

  val md = MarkData("subj", None)
  val mNotShared = Mark(sharer.id, mark = md)
  val mUserRead = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(userRead, None)))
  val mEmailRW = Mark(sharer.id, mark = md, sharedWith = Some(SharedWith(None, emailRW)))
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

  it should "(UNIT) authorize sharing" in {
    mNotShared.isAuthorizedShare(sharer) shouldBe true // owner can choose to share with anyone
    mUserRead.isAuthorizedShare(sharer) shouldBe true
    mPublicRead.isAuthorizedShare(sharee) shouldBe true
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

  it should "(UNIT) prevent sharing" in {
    mNotShared.isAuthorizedShare(sharee) shouldBe false
    mEmailRW.isAuthorizedShare(sharee) shouldBe false // only public can be shared by sharee
  }
}
