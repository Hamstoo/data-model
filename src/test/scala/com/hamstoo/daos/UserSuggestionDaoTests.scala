package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserSuggestion
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import org.scalatest.OptionValues

class UserSuggestionDaoTests
  extends FlatSpecWithMatchers
    with FutureHandler
    with OptionValues
    with MongoEnvironment {

  val uuid: UUID = constructUserId()
  val username = Some("bimbo")
  val email = Some("bidno@gmail.com")
  val us0 = UserSuggestion(uuid, username, None)
  val us1 = UserSuggestion(uuid, None, email)

  "UserSuggestionDao" should "insert user suggestion" in {
    userSuggDao.save(us0).futureValue shouldEqual us0
    userSuggDao.save(us1).futureValue shouldEqual us1
  }

  it should "retrieve user suggestion by username/email" in {
    userSuggDao.retrieve(uuid, username, None).futureValue.value shouldEqual us0
    userSuggDao.retrieve(uuid, None, email).futureValue.value shouldEqual us1
  }

  it should "increase count of shares" in {
    userSuggDao.save(us0).futureValue shouldEqual us0.copy(count = 2)
    userSuggDao.retrieve(uuid, Some("bimbo"), None).futureValue.value.count shouldEqual 2
  }

  it should "retrieve by username prefix" in {
    userSuggDao.findSuggestions(uuid, "bi").futureValue.map(_.id) shouldEqual Seq(us0, us1).map(_.id)
  }

}
