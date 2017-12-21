package com.hamstoo.daos.auth

import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import org.scalatest.OptionValues

/**
  * CRUD Unit tests for class MongoPasswordInfoDao
  */
class MongoPasswordInfoDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._
  val passInfo = PasswordInfo("token", "secret")

  "MongoOAuth1InfoDao" should "(UNIT) add auth1 info" in {
    userDao.save(userA).futureValue shouldEqual {}
    passDao.add(loginInfoA, passInfo).futureValue shouldEqual passInfo
  }

  it should "(UNIT) find auth1 info" in {
    passDao.find(loginInfoA).futureValue.value shouldEqual passInfo
  }

  it should "(UNIT) remove auth1 info" in {
    passDao.remove(loginInfoA).futureValue shouldEqual {}
  }
}
