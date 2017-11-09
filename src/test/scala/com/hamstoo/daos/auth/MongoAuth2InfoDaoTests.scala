package com.hamstoo.daos.auth

import java.util.UUID

import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import org.scalatest.OptionValues

/**
  * CRUD Unit tests for class MongoOAuth2InfoDao
  */
class MongoAuth2InfoDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._

  // TODO: shouldn't this test fail if these are different?
  val u0: UUID = constructUserId()
  val u1: UUID = constructUserId()

  val provider = "some provider"
  val loginInfo = LoginInfo(provider, u0.toString)
  val auth2Info = OAuth2Info("access token")
  val user = User(u1, UserData(), Profile(loginInfo, confirmed = true, None, None, None, None) :: Nil)

  "MongoOAuth2InfoDao" should "(UNIT) add auth2 info" in {
    userDao.save(user).futureValue shouldEqual {}
    auth2Dao.add(loginInfo, auth2Info).futureValue shouldEqual auth2Info
  }

  it should "(UNIT) find auth2 info" in {
    auth2Dao.find(loginInfo).futureValue.value shouldEqual auth2Info
  }

  it should "(UNIT) remove auth2 info" in {
    auth2Dao.remove(loginInfo).futureValue shouldEqual {}
  }
}
