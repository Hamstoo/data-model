/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.{UserData, _}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import com.mohiva.play.silhouette.api.LoginInfo
import org.scalatest.OptionValues

/**
  * UserDaoTests
  */
class UserDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  val providerId = "some-provider-id"
  val providerKey = "some-provider-key"

  val newProviderId = "new-provider-id"
  val newProviderKey = "new-provider-key"

  val email = "random@gmail.com"
  val newEmail = "new.random@gmail.com"

  val loginInfo = LoginInfo(providerId, providerKey)
  val newLoginInfo = LoginInfo(newProviderId, newProviderKey)

  val profile = Profile(loginInfo, confirmed = false, Some(email), None, None, None)
  val newProfile = Profile(newLoginInfo, confirmed = true, Some(newEmail), None, None, None)

  val user = User(constructUserId(), UserData(username = Some("namenamename")), List(profile))

  "MongoUserDao" should "(UNIT) create user" in {
    userDao.save(user).futureValue shouldEqual {}
  }

  it should "(UNIT) retrieve user by login info" in {
    userDao.retrieve(loginInfo).futureValue.get shouldEqual user
  }

  it should "(UNIT) retrieve user by email" in {
    userDao.retrieveByEmail(email).futureValue.get shouldEqual user
  }

  it should "(UNIT) retrieve user by UUID" in {
    userDao.retrieveById(user.id).futureValue.get shouldEqual user
  }

  it should "(UNIT) link profile to user" in {
    val linkedUser = user.copy(profiles = List(profile, newProfile))
    userDao.link(user.id, newProfile).futureValue shouldEqual linkedUser
    userDao.retrieve(newProfile.loginInfo).futureValue.get shouldEqual linkedUser
  }

  it should "(UNIT) unlink profile from user" in {
    userDao.unlink(user.id, newProfile.loginInfo).futureValue shouldEqual user
    userDao.retrieve(newProfile.loginInfo).futureValue shouldEqual None
  }

  it should "(UNIT) confirm user profile" in {
    val confirmedUser = user.copy(profiles = List(profile.copy(confirmed = true)))
    userDao.confirm(profile.loginInfo).futureValue shouldEqual confirmedUser
    userDao.retrieve(profile.loginInfo).futureValue.get shouldEqual confirmedUser
  }

  it should "(UNIT) update profile" in {
    val updProfile = newProfile.copy(loginInfo = profile.loginInfo)
    val updateUser = user.copy(profiles = List(updProfile))
    userDao.update(updProfile).futureValue shouldEqual updateUser
    userDao.retrieve(profile.loginInfo).futureValue.get shouldEqual updateUser
  }

  it should "(UNIT) delete user" in {
    userDao.delete(user.id).futureValue shouldEqual {}
    userDao.retrieveById(user.id).futureValue shouldEqual None
  }
}
