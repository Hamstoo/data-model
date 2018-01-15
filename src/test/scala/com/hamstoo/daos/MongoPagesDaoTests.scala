package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Page, Representation}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import org.scalatest.OptionValues

import scala.collection.immutable.Nil

class MongoPagesDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  val uuid: UUID = constructUserId()
  val oldId: String = constructMarkId()
  val newId: String = constructMarkId()

  val privatePage = Page(uuid, oldId, Representation.PRIVATE, "Hello private".toCharArray.map(_.toByte))
  val publicPage = Page(uuid, newId, Representation.PUBLIC, "Hello public".toCharArray.map(_.toByte))
  val userPage = Page(uuid, newId, Representation.USERS, "Hello users".toCharArray.map(_.toByte))

  "MongoPagesDao" should "insert page" in {
    pagesDao.insertPage(privatePage).futureValue shouldEqual privatePage
  }

  it should "insert stream of pages" in {
    pagesDao.bulkInsertPages(Seq(publicPage, userPage).toStream).futureValue shouldEqual 2
  }

  it should "retrieve public page" in {
    pagesDao.retrievePublicPage(uuid, newId).futureValue.value shouldEqual publicPage
  }

  it should "retrieve user page" in {
    pagesDao.retrieveUserPage(uuid, newId).futureValue.value shouldEqual userPage
  }

  it should "retrieve private pages" in {
    pagesDao.retrievePrivatePages(uuid, oldId).futureValue shouldEqual Seq(privatePage)
  }

  it should "retrieve all pages" in {
    pagesDao.retrieveAllPages(uuid, oldId).futureValue shouldEqual Seq(privatePage)

    pagesDao.retrieveAllPages(uuid, newId).futureValue shouldEqual Seq(publicPage, userPage)
  }

  it should "remove user page" in {
    pagesDao.removeUserPage(uuid, newId).futureValue shouldEqual {}

    pagesDao.retrieveUserPage(uuid, newId).futureValue shouldEqual None
  }

  it should "remove public page" in {
    pagesDao.removePublicPage(uuid, newId).futureValue shouldEqual {}

    pagesDao.retrievePublicPage(uuid, newId).futureValue shouldEqual None
  }

  it should "remove private pages" in {
    pagesDao.removePrivatePage(uuid, oldId).futureValue shouldEqual {}

    pagesDao.retrievePrivatePages(uuid, oldId).futureValue shouldEqual Nil
  }
}
