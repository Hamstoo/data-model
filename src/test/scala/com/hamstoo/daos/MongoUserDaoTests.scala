package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.{UserData, _}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils
import com.hamstoo.utils.DataInfo._
import com.mohiva.play.silhouette.api.LoginInfo
import org.scalatest.OptionValues
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.time.{Seconds, Span}

import scala.util.Random

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 10.11.17
  */
class MongoUserDaoTests
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
    userDao.retrieve(email).futureValue.get shouldEqual user
  }

  it should "(UNIT) retrieve user by UUID" in {
    userDao.retrieve(user.id).futureValue.get shouldEqual user
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
    userDao.retrieve(user.id).futureValue shouldEqual None
  }

  it should "(UNIT) create user and find him by username with `hasSharedMarks` == false condition" in {
    userDao.save(user.copy(id = constructUserId(), UserData(username = Some("dfgdfg")))).futureValue shouldEqual {}
    userDao.searchUsernamesBySuffix("dfg", false, constructUserId(), None).futureValue.toList(0).username.contains("dfg") shouldEqual true
  }

  it should "(UNIT) create user and sheuserNameGeneratedll not find him by username with `hasSharedMarks` == true condition" in {
    val loginInfo2 = LoginInfo(constructUserId().toString, constructUserId().toString)
    val profile2 = Profile(loginInfo2, confirmed = false, Some(email), None, None, None)
    val newUser =  User(constructUserId(), UserData(username = Some("yuiyui")), List(profile2))
    userDao.save(newUser).futureValue shouldEqual {}
    userDao.searchUsernamesBySuffix("yui", true, constructUserId(), None).futureValue.size shouldEqual 0
  }

  it should "(UNIT) create users and find specific users by username with `hasSharedMarks` == true and users are LISTED condition" in {
    // create a user who is owning mark
    def randomString(length: Int = 10) = utils.generateDbId(length).toString
    val s1 = randomString()
    val s2 = randomString()
    s1.equals(s2) shouldBe false

    val uNameSuffix = randomString()
    val userNameGenerated = randomString()+uNameSuffix+randomString()
    val loginInfo2 = LoginInfo(randomString(), randomString())
    val profile2 = Profile(loginInfo2, confirmed = true, Some(randomString()+"somemail.com"), Some("Some FN"), Some("Some LN"), None )
    val sharingUser = User(constructUserId(), UserData(username = Some(userNameGenerated), usernameLower = Some(userNameGenerated.toLowerCase())), List(profile2))
    userDao.save(sharingUser).futureValue

    // add user mark
    val reprInfoUsr = ReprInfo(constructUserId().toString, ReprType.PUBLIC)
    val reprs = Seq(reprInfoUsr)
    val m1 = Mark(sharingUser.id, constructUserId().toString, MarkData(randomString()+" a subject", Some("http://www."+randomString(20)+".com"),
      pagePending = Some(false)), reprs = reprs)
    marksDao.insert(m1).futureValue shouldEqual m1


    // create another user whom a mark will be shared with
    val email = randomString()+"@gmail.com"
    val loginInfo3 = LoginInfo(randomString(), randomString())
    val profile3 = Profile(loginInfo3, confirmed =  true, Some(email), Some(randomString()), Some(randomString()), Some(randomString()))
    val newUser =  User(constructUserId(), UserData(Some(randomString()),Some(email),Some(email)), List(profile3))
    userDao.save(newUser).futureValue


    // share mark with another user (newUser)
    val optUserGroup = Some(UserGroup(emails = Some(Set(email)), sharedObjs = Seq(UserGroup.SharedObj(m1.id, System.currentTimeMillis()))))
    marksDao.updateSharedWith(m1, 1, Some((SharedWith.Level.LISTED, optUserGroup)), Some((SharedWith.Level.LISTED, optUserGroup))).
      futureValue.id shouldEqual m1.id

    // check if mark is created and if is created "shareWith" object and contains sharee email
    val markUpdatedToShare = marksDao.retrieveInsecure(m1.id).futureValue
    markUpdatedToShare.get.sharedWith.isEmpty shouldEqual false

    val emails = userDao.retrieveGroup(markUpdatedToShare.get.sharedWith.get.readOnly.get.group.get).futureValue.get.emails.get
    emails.contains(email) shouldEqual true
    //Thread.sleep(90000)
    // use another user data to find usernames who has shared mark with him
   // userDao.searchUsernamesBySuffix(uNameSuffix, true, newUser.id, Some(email)).futureValue.toList(0)

    val unameLower = userDao.searchUsernamesBySuffix(uNameSuffix, true, newUser.id, Some(email)).futureValue(Interval(Span(60, Seconds))).toList(0).username.toLowerCase
    unameLower.indexOf(uNameSuffix)  shouldEqual 10

    }

  it should "(UNIT) create users and find specific users by username with `hasSharedMarks` == true and mark being PUBLIC shared condition" in {
    // create a user who is owning public mark
    val loginInfo2 = LoginInfo(constructUserId().toString, constructUserId().toString)
    val profile2 = Profile(loginInfo2, confirmed = false, None, None, None, None)
    val sharingUser = User(constructUserId(), UserData(username = Some("cvbr")), List(profile2))
    userDao.save(sharingUser).futureValue shouldEqual {}

    // add user mark
    val reprInfoUsr = ReprInfo("reprId212312", ReprType.USER_CONTENT)
    val reprs = Seq(reprInfoUsr)
    val m1 = Mark(sharingUser.id, "m123id", MarkData("a subject1213123", Some("http://www.som12312312eurl323.com"),
      pagePending = Some(true)), reprs = reprs)
    marksDao.insert(m1).futureValue shouldEqual m1

    // change mark to be public
    marksDao.updateSharedWith(m1, 1, Some((SharedWith.Level.PUBLIC, None)), Some((SharedWith.Level.PUBLIC, None ))).
      futureValue.id shouldEqual m1.id

    // look for usernames of users who has public marks
    userDao.searchUsernamesBySuffix("cvbr", true, constructUserId(), None).futureValue.toList(0).username.contains("cvbr") shouldEqual true
  }
}
