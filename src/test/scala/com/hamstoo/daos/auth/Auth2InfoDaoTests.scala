/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.daos.auth

import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import org.scalatest.OptionValues

/**
  * CRUD Unit tests for class MongoOAuth2InfoDao
  */
class Auth2InfoDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  import com.hamstoo.utils.DataInfo._
  val auth2Info = OAuth2Info("access token")

  "MongoOAuth2InfoDao" should "(UNIT) add auth2 info" in {
    userDao.save(userA).futureValue shouldEqual {}
    auth2Dao.add(loginInfoA, auth2Info).futureValue shouldEqual auth2Info
  }

  it should "(UNIT) find auth2 info" in {
    auth2Dao.find(loginInfoA).futureValue.get shouldEqual auth2Info
  }

  it should "(UNIT) remove auth2 info" in {
    auth2Dao.remove(loginInfoA).futureValue shouldEqual {}
  }
}
