package com.hamstoo.daos.auth

import java.util.UUID

import com.hamstoo.daos.MongoUserDao
import com.hamstoo.models.{Profile, User, UserData}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper}
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import org.scalatest.OptionValues

class MongoPasswordInfoDaoTest
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues
    with TestHelper {

  lazy val userDao = new MongoUserDao(getDB)
  lazy val passDao = new MongoPasswordInfoDao(getDB)

  val provider = "some provider"
  val uuid: UUID = UUID.randomUUID()

  val loginInfo = LoginInfo(provider, uuid.toString)

  val passInfo = PasswordInfo("token", "secret")

  val user = User(uuid, UserData(), Profile(loginInfo, confirmed = true, None, None, None, None) :: Nil)

  "MongoOAuth1InfoDao" should "* (UNIT) add auth1 info" in {
    userDao.save(user).futureValue shouldEqual {}

    passDao.add(loginInfo, passInfo).futureValue shouldEqual passInfo
  }

  it should "* (UNIT) find auth1 info" in {
    passDao.find(loginInfo).futureValue.value shouldEqual passInfo
  }

  it should "* (UNIT) remove auth1 info" in {
    passDao.remove(loginInfo).futureValue shouldEqual {}
  }
}
