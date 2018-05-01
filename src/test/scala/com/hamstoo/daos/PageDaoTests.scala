/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models.{Mark, MarkData, Page}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._
import org.scalatest.OptionValues
import reactivemongo.core.errors.DatabaseException

import scala.collection.immutable.Nil
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Failure

/**
  * All classes have ScalaDoc!
  */
class PageDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  val uuid: UUID = constructUserId()
  val mOld = Mark(uuid, constructMarkId(), MarkData("", None))
  val mNew = Mark(uuid, constructMarkId(), MarkData("", None))
  val mMerge = Mark(uuid, constructMarkId(), MarkData("", None))

  val mergeContent: Array[Byte] = "Hello new public".toCharArray.map(_.toByte)

  val privatePage = Page(mOld.id, ReprType.PRIVATE, "Hello private".toCharArray.map(_.toByte))
  val publicPage = Page(mNew.id, ReprType.PUBLIC, "Hello public".toCharArray.map(_.toByte))
  val userPage = Page(mNew.id, ReprType.USER_CONTENT, "Hello users".toCharArray.map(_.toByte))

  "MongoPagesDao" should "(UNIT) throw exceptions when trying to insert pages for nonexistent marks" in {
    Await.ready(pagesDao.insertPage(privatePage), 10 seconds).onComplete {
      case Failure(e) => e shouldBe a [NoSuchElementException]
      case _ => assert(false)
    }
  }

  it should "(UNIT) insert pages" in {
    marksDao.insert(mOld).futureValue shouldEqual mOld
    marksDao.insert(mNew).futureValue shouldEqual mNew

    // this will have happened already in first test, even though it threw an exception, and so it should throw a
    // unique key DatabaseException here
    Await.ready(pagesDao.insertPage(privatePage), 10 seconds).onComplete {
      case Failure(e) => e shouldBe a [DatabaseException]
      case _ => assert(false)
    }

    pagesDao.insertPage(publicPage).futureValue shouldEqual publicPage
    pagesDao.insertPage(userPage).futureValue shouldEqual userPage
  }

  it should "(UNIT) retrieve public page" in {
    pagesDao.retrievePages(mNew.id, ReprType.PUBLIC).futureValue.head shouldEqual publicPage
  }

  it should "(UNIT) retrieve user page" in {
    pagesDao.retrievePages(mNew.id, ReprType.USER_CONTENT).futureValue.head shouldEqual userPage
  }

  it should "(UNIT) retrieve private pages" in {
    pagesDao.retrievePages(mOld.id, ReprType.PRIVATE).futureValue shouldEqual Seq(privatePage)
  }

  /*it should "(UNIT) retrieve all pages" in {
    pagesDao.retrieveAllPages(uuid, oldId).futureValue shouldEqual Seq(privatePage)
    pagesDao.retrieveAllPages(uuid, newId).futureValue shouldEqual Seq(publicPage, userPage)
  }*/

  it should "(UNIT) merge private pages" in {
    marksDao.insert(mMerge).futureValue shouldEqual mMerge
    val mergePage = privatePage.copy(markId = mMerge.id, content = mergeContent, id = constructMarkId())
    pagesDao.insertPage(mergePage).futureValue.markId shouldEqual mMerge.id
    pagesDao.mergePrivatePages(mOld.id, uuid, mMerge.id).futureValue shouldEqual {}
    pagesDao.retrievePages(mMerge.id, ReprType.PRIVATE).futureValue shouldEqual Nil
    val privRepr = pagesDao.retrievePages(mOld.id, ReprType.PRIVATE).futureValue.map(_.markId)
    privRepr.size shouldEqual 2
  }
}
