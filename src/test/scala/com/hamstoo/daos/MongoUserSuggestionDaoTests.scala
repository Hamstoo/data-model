package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserSuggestion
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import org.scalatest.OptionValues

class MongoUserSuggestionDaoTests
  extends FlatSpecWithMatchers
    with FutureHandler
    with OptionValues
    with MongoEnvironment {

  val uuid: UUID = constructUserId()
  val us = UserSuggestion(uuid, Some("lukang"), None)

  "UserSuggestionDao" should "insert user suggestion" in {
    userSuggDao.insert(us).futureValue shouldEqual us
  }

  it should "retrieve by username prefix" in {
    userSuggDao.retrieveByUsername(uuid, "lu").futureValue.value shouldEqual us
  }

}
