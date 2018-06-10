package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo.constructUserId
import com.mohiva.play.silhouette.api.LoginInfo
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global

class UserSuggestionDaoTests
  extends FlatSpecWithMatchers
    with FutureHandler
    with OptionValues
    with MongoEnvironment {

  val userId: UUID = constructUserId()
  val userLoginInfo = LoginInfo("user-provider-id", "user-provider-key")
  val userProfile = Profile(userLoginInfo, confirmed = false, Some("user@gmail.com"), None, None, None)
  val userData = UserData(username = Some("user"))
  val user = User(userId, userData, userProfile :: Nil)

  val shareeUserId: UUID = constructUserId()
  val shareeLoginInfo = LoginInfo("sharee-provider-id", "sharee-provider-key")
  val shareeProfile = Profile(shareeLoginInfo, confirmed = false, Some("sharee@gmail.com"), None, None, None)
  val shareeData = UserData(username = Some("sharee"))
  val sharee = User(shareeUserId, shareeData, List(shareeProfile))

  val sharee1UserId: UUID = constructUserId()
  val sharee1LoginInfo = LoginInfo("sharee1-provider-id", "sharee1-provider-key")
  val sharee1Profile = Profile(sharee1LoginInfo, confirmed = false, Some("sharee1@gmail.com"), None, None, None)
  val sharee1Data = UserData(username = Some("sharee1"))
  val sharee1 = User(sharee1UserId, sharee1Data, List(sharee1Profile))

  "UserSuggestionDao" should "update suggestion timestamps" in {

    userDao.save(sharee).futureValue shouldEqual {}

    (for {
      a <- userSuggDao.save(userId, shareeProfile.email)
      b <- userSuggDao.save(userId, shareeProfile.email)
    } yield a.ts should be < b.ts).futureValue
  }

  it should "update suggestion usernames by email" in {
    val newUsername = "newUsername0"
    val newUserData = UserData(username = Some(newUsername))

    userDao.save(sharee.copy(userData = newUserData)).futureValue shouldEqual {}
    userSuggDao.updateUsernamesByEmail(shareeProfile.email.get).futureValue shouldEqual 1

    userSuggDao
      .retrieveSuggestion(userId, Some("n"))
      .futureValue
      .flatMap(_.shareeUsername) shouldEqual Seq(newUsername)
  }

  it should "update suggestion usernames by username" in {
    val newUsername = "saree"
    val newUserData = UserData(username = Some(newUsername))

    userDao.save(sharee.copy(userData = newUserData)).futureValue shouldEqual {}
    userSuggDao.updateUsernamesByUsername("newUsername0", newUsername).futureValue shouldEqual 1

    userSuggDao
      .retrieveSuggestion(userId, Some("s"))
      .futureValue
      .flatMap(_.shareeUsername) shouldEqual Seq(newUsername)
  }

   it should "retrieve suggestion" in {
    userDao.save(sharee1).futureValue shouldEqual {}

    userSuggDao
      .save(userId, sharee1Profile.email)
      .futureValue
      .shareeUsername
      .value shouldEqual "sharee1"

    userSuggDao
      .retrieveSuggestion(userId, Some("s"))
      .futureValue
      .flatMap(_.shareeUsername) shouldEqual Seq("sharee1", "saree")
  }

  it should "retrieve who make share for user" in {
    userDao.save(user).futureValue shouldEqual {}

    userSuggDao
      .retrieveWhoShared(shareeUserId)
      .futureValue shouldEqual Seq("@user")
  }

  it should "delete suggestions" in {
    userSuggDao.delete(userId, Some("sh")).futureValue shouldEqual {}

    userSuggDao
      .retrieveSuggestion(userId, Some("sa"))
      .futureValue
      .flatMap(_.shareeUsername) shouldEqual Seq("saree")
  }
}
