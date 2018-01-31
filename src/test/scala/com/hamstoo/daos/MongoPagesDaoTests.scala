package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
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
  val mergeId: String = constructMarkId()

  val mergeContent: Array[Byte] = "Hello new public".toCharArray.map(_.toByte)

  val privatePage = Page(uuid, oldId, ReprType.PRIVATE, "Hello private".toCharArray.map(_.toByte))
  val publicPage = Page(uuid, newId, ReprType.PUBLIC, "Hello public".toCharArray.map(_.toByte))
  val userPage = Page(uuid, newId, ReprType.USER_CONTENT, "Hello users".toCharArray.map(_.toByte))

  "MongoPagesDao" should "(UNIT) insert pages" in {
    pagesDao.insertPage(privatePage).futureValue shouldEqual privatePage
    pagesDao.insertPage(publicPage).futureValue shouldEqual publicPage
    pagesDao.insertPage(userPage).futureValue shouldEqual userPage
  }

  it should "(UNIT) retrieve public page" in {
    pagesDao.retrievePages(uuid, newId, ReprType.PUBLIC).futureValue.head shouldEqual publicPage
  }

  it should "(UNIT) retrieve user page" in {
    pagesDao.retrievePages(uuid, newId, ReprType.USER_CONTENT).futureValue.head shouldEqual userPage
  }

  it should "(UNIT) retrieve private pages" in {
    pagesDao.retrievePages(uuid, oldId, ReprType.PRIVATE).futureValue shouldEqual Seq(privatePage)
  }

  it should "(UNIT) retrieve all pages" in {
    pagesDao.retrieveAllPages(uuid, oldId).futureValue shouldEqual Seq(privatePage)
    pagesDao.retrieveAllPages(uuid, newId).futureValue shouldEqual Seq(publicPage, userPage)
  }

  it should "(UNIT) merge private pages" in {
    val mergePage = privatePage.copy(markId =  mergeId, content = mergeContent)
    pagesDao.insertPage(mergePage).futureValue.markId shouldEqual mergeId
    pagesDao.mergePrivatePages(oldId, uuid, mergeId).futureValue shouldEqual {}
    pagesDao.retrievePages(uuid, mergeId, ReprType.PRIVATE).futureValue shouldEqual Nil
    val privRepr = pagesDao.retrievePages(uuid, oldId, ReprType.PRIVATE).futureValue.map(_.markId)
    privRepr.size shouldEqual 2
  }

  it should "(UNIT) remove user page" in {
    val pg = pagesDao.retrievePages(uuid, newId, ReprType.USER_CONTENT).futureValue.head
    pagesDao.removePage(pg.u_id).futureValue shouldEqual {}
    pagesDao.retrievePages(uuid, newId, ReprType.USER_CONTENT).futureValue shouldEqual Nil
  }

  it should "(UNIT) remove public page" in {
    val pg = pagesDao.retrievePages(uuid, newId, ReprType.PUBLIC).futureValue.head
    pagesDao.removePage(pg.u_id).futureValue shouldEqual {}
    pagesDao.retrievePages(uuid, newId, ReprType.PUBLIC).futureValue shouldEqual Nil
  }

  it should "(UNIT) remove private pages" in {
    val pg = pagesDao.retrievePages(uuid, oldId, ReprType.PRIVATE).futureValue.head
    pagesDao.removePage(pg.u_id).futureValue shouldEqual {}
    pagesDao.retrievePages(uuid, oldId, ReprType.PRIVATE).futureValue shouldEqual Nil
  }

  it should "(UNIT) remove single page" in {
    pagesDao.insertPage(privatePage).futureValue shouldEqual privatePage
    val retrieved = pagesDao.retrievePages(privatePage.userId, privatePage.markId, ReprType.PRIVATE).futureValue.head
    retrieved shouldEqual privatePage
    pagesDao.removePage(privatePage.u_id).futureValue shouldEqual {}
    pagesDao.retrievePages(privatePage.userId, privatePage.markId, ReprType.PRIVATE).futureValue shouldEqual Nil
  }
}
