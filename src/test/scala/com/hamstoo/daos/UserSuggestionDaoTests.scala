package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import com.mohiva.play.silhouette.api.LoginInfo
import org.scalatest.OptionValues

/**
  * UserSuggestionDaoTests
  */
class UserSuggestionDaoTests
  extends FlatSpecWithMatchers
    with FutureHandler
    with OptionValues
    with MongoEnvironment {

  val userId: UUID = constructUserId()

  // use the same user as owner and sharee
  val loginInfo = LoginInfo("some-provider-id", "some-provider-key")
  val profile = Profile(loginInfo, confirmed = false, Some("dino@gmail.com"), None, None, None)
  val userData = UserData(username = Some("DuMbO"))
  val user = User(userId, userData, List(profile))

  override def beforeAll(): Unit = {
    super.beforeAll()
    userDao.save(user).futureValue
  }

  def testIt(sharee: Option[String], shareeUsername: Option[String]): Unit = {
    userSuggDao.save(userId, sharee).futureValue.shareeUsername shouldEqual shareeUsername
    userSuggDao.retrieveUserSuggestions(userId, "du").futureValue shouldEqual Seq(userData.username.get)
    userSuggDao.delete(userId, sharee).futureValue
    userSuggDao.retrieveUserSuggestions(userId, "du").futureValue shouldEqual Seq()
  }

  "UserSuggestionDao" should "retrieve search suggestions from public share" in {
    testIt(None, None)
  }

  it should "retrieve search suggestions from private email sharee share" in {
    testIt(profile.email, userData.username)
  }

  it should "retrieve search suggestions from private username sharee share" in {
    testIt(userData.username, userData.username)
  }

  it should "retrieve search suggestions from private lowercase username sharee share" in {
    testIt(userData.username.map(_.toLowerCase), userData.username)
  }

  it should "update search suggestion timestamps" in {
    val a = userSuggDao.save(userId, None).futureValue
    Thread.sleep(5)
    (userSuggDao.save(userId, None).futureValue.ts - a.ts) shouldBe >= (5L)
    userSuggDao.delete(userId, None).futureValue

    val b = userSuggDao.save(userId, profile.email).futureValue
    Thread.sleep(5)
    (userSuggDao.save(userId, profile.email).futureValue.ts - b.ts) shouldBe >= (5L)
    userSuggDao.delete(userId, profile.email).futureValue
  }

  it should "update usernames" in {
    userSuggDao.save(userId, profile.email).futureValue
    val newUserData = UserData(username = Some("piGleT"))
    userDao.save(user.copy(userData = newUserData)).futureValue
    userSuggDao.updateUsernamesByEmail(profile.email.get).futureValue shouldEqual 1

    // only the shareeUsername, not the ownerUsername, will have been updated so far (so we can still search w/ "du")
    userSuggDao.retrieveUserSuggestions(userId, "du").futureValue shouldEqual Seq(userData.username.get)

    // now update the ownerUsername, which will be returned by retrieveUserSuggestions
    userSuggDao.updateUsernamesByUsername(userData.username.get, newUserData.username.get).futureValue shouldEqual 1
    userSuggDao.retrieveUserSuggestions(userId, "Pi").futureValue shouldEqual Seq(newUserData.username.get)
  }
}
