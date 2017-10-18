package com.hamstoo.daos.auth

import java.util.UUID

import com.hamstoo.daos.MongoUserDao
import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import org.scalatest.OptionValues

class MongoAuth1InfoDaoTest
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  lazy val userDao = new MongoUserDao(getDB)
  lazy val auth1Dao = new MongoOAuth1InfoDao(getDB)

  val provider = "some provider"
  val uuid: UUID = UUID.randomUUID()

  val loginInfo = LoginInfo(provider, uuid.toString)

  val auth1Info = OAuth1Info("token", "secret")

  val user = User(uuid, UserData(), Profile(loginInfo, confirmed = true, None, None, None, None) :: Nil)

  "MongoOAuth1InfoDao" should "* (UNIT) add auth1 info" in {
    userDao.save(user).futureValue shouldEqual {}

    auth1Dao.add(loginInfo, auth1Info).futureValue shouldEqual auth1Info
  }

  it should "* (UNIT) find auth1 info" in {
    auth1Dao.find(loginInfo).futureValue.value shouldEqual auth1Info
  }

  it should "* (UNIT) remove auth1 info" in {
    auth1Dao.remove(loginInfo).futureValue shouldEqual {}
  }
}
