package com.hamstoo.daos.auth

import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import org.scalatest.OptionValues

/**
  * CRUD Unit tests for class MongoOAuth1InfoDao
  */
class MongoAuth1InfoDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._
  val auth1Info = OAuth1Info("token", "secret")

  "MongoOAuth1InfoDao" should "(UNIT) add auth1 info" in {
    userDao.save(userA).futureValue shouldEqual {}
    auth1Dao.add(loginInfoA, auth1Info).futureValue shouldEqual auth1Info
  }

  it should "(UNIT) find auth1 info" in {
    auth1Dao.find(loginInfoA).futureValue.value shouldEqual auth1Info
  }

  it should "(UNIT) remove auth1 info" in {
    auth1Dao.remove(loginInfoA).futureValue shouldEqual {}
  }
}
