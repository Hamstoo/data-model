package com.hamstoo.daos.auth

import java.util.UUID

import com.hamstoo.daos.MongoUserDao
import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.TestHelper
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import org.scalatest.OptionValues

class MongoAuth2InfoDaoTest
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  lazy val userDao = new MongoUserDao(getDB)
  lazy val auth2Dao = new MongoOAuth2InfoDao(getDB)

  val provider = "some provider"
  val uuid: UUID = UUID.randomUUID()

  val loginInfo = LoginInfo(provider, uuid.toString)

  val auth2Info = OAuth2Info("access token")

  val user = User(uuid, UserData(), Profile(loginInfo, confirmed = true, None, None, None, None) :: Nil)


  "MongoOAuth2InfoDao" should "* (UNIT) add auth2 info" in {
    userDao.save(user).futureValue shouldEqual {}

    auth2Dao.add(loginInfo, auth2Info).futureValue shouldEqual auth2Info
  }

  it should "* (UNIT) find auth2 info" in {
    auth2Dao.find(loginInfo).futureValue.value shouldEqual auth2Info
  }

  it should "* (UNIT) remove auth2 info" in {
    auth2Dao.remove(loginInfo).futureValue shouldEqual {}
  }
}
