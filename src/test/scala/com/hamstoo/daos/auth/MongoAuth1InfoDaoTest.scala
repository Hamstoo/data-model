package com.hamstoo.daos.auth

import java.util.UUID

import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.TestHelper
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import org.scalatest.OptionValues

/**
  * CRUD Unit tests for class MongoOAuth1InfoDao
  */
class MongoAuth1InfoDaoTest
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  // TODO: shouldn't this test fail if these are different?
  val u0: UUID = constructUserId()
  val u1: UUID = constructUserId()

  val provider = "some provider"
  val loginInfo = LoginInfo(provider, u0.toString)
  val auth1Info = OAuth1Info("token", "secret")
  val user = User(u1, UserData(), Profile(loginInfo, confirmed = true, None, None, None, None) :: Nil)

  "MongoOAuth1InfoDao" should "(UNIT) add auth1 info" in {
    userDao.save(user).futureValue shouldEqual {}
    auth1Dao.add(loginInfo, auth1Info).futureValue shouldEqual auth1Info
  }

  it should "(UNIT) find auth1 info" in {
    auth1Dao.find(loginInfo).futureValue.value shouldEqual auth1Info
  }

  it should "(UNIT) remove auth1 info" in {
    auth1Dao.remove(loginInfo).futureValue shouldEqual {}
  }
}
