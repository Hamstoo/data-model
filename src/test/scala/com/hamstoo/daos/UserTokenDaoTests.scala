/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserToken
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 12.11.17
  */
class UserTokenDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  val userUUID: UUID = UUID.randomUUID()
  val userMail = "some@gmail.com"
  val token = UserToken(userId = userUUID, email = userMail, isSignUp = false)

  "MongoUserTokenDao" should "insert token" in {
    tokenDao.insert(token).futureValue shouldEqual {}
  }

  it should "retrieve user token" in {
    tokenDao.retrieve(token.id).futureValue.get shouldEqual token
  }

  it should "remove user token" in {
    tokenDao.remove(token.id).futureValue shouldEqual {}

    tokenDao.retrieve(token.id).futureValue shouldEqual None
  }
}
