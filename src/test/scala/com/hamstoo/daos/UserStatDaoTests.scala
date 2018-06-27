/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Injector
import com.hamstoo.models._
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.test.FutureHandler
import com.hamstoo.utils.DataInfo
import org.joda.time.DateTime
import org.scalatest.OptionValues
import reactivemongo.bson.{BSONDocument, BSONDocumentReader, Macros}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
  * This class provides unit tests for UserStatDao.
  * 2 tests in this class are responsible for testing total marks particular users.
  * They are designed to test the counting of marks after marks modifications operations.
  */
class UserStatDaoTests extends AkkaMongoEnvironment("UserStatDaoTests-ActorSystem")
    with OptionValues
    with FutureHandler {

  implicit val ex: ExecutionContext = system.dispatcher

  // create a Guice object graph configuration/module and instantiate it to an injector
  lazy implicit val injector: Injector = DataInfo.createExtendedInjector()

  // only for test purpose, used in serialization section
  case class Test(_id: String, imports: Int)

  // construct a new userId for these tests alone
  val userId: UUID = DataInfo.constructUserId()
  val mbUser = User(userId)

  val tagSet = Some(Set("tag1asdasda, tag2adasd"))
  val cmt = Some("Queryasdasd")
  val pubRepr = Some("reprasdsad")
  val newMarkData = MarkData("a NEW subjфывыфвect1", Some("https://github.com"))
  val m1 = Mark(userId, timeFrom = DateTime.now.minusDays(10).getMillis,
                mark = MarkData("a subjasdect342", Some("http://hamstoo231321.com"), tags = tagSet, comment = cmt))
  val m2 = Mark(userId, mark = MarkData("a subasdject1", Some("http://hamstoo223.com"), tags = tagSet))
  val m3 = Mark(userId, mark = MarkData("a subasdject1asdasd", Some("http://hamstooasdasd223.com"), tags = tagSet))

  /** This test is designed to create two marks for user. */
  "MongoUserStatsDao" should "(UNIT) calculate marks inserted by userId" in {
    val totalMarks: ProfileDots = (for {
      mi1 <- marksDao.insert(m1)
      mi2 <- marksDao.insert(m2)
      totalMarks <- userStatsDao.profileDots(userId, 0, injector)
    } yield totalMarks).futureValue

    totalMarks.nMarks shouldEqual 2
    totalMarks.marksLatest.map(_.nMarks).sum shouldEqual 2
  }

  /** This test is designed to modify marks, but to keep only two actual marks for user. */
  it should "(UNIT) calculate marks updated by userId" in {
    val totalMarks: ProfileDots = (for {
      mi1 <- marksDao.update(mbUser, m1.id, m2.mark)
      mi2 <- marksDao.update(mbUser, m2.id, m1.mark)
      mi3 <- marksDao.update(mbUser, m1.id, m1.mark)
      mi4 <- marksDao.update(mbUser, m2.id, m2.mark)
      mi5 <- marksDao.insert(m3)
      intResult <- marksDao.delete(userId, m2.id :: Nil)
      totalMarks <- userStatsDao.profileDots(userId, 0, injector)
    } yield totalMarks).futureValue

    totalMarks.nMarks shouldEqual 2
    totalMarks.marksLatest.map(_.nMarks).sum shouldEqual 2
  }

  it should "increment user's imports count" in {
    implicit val handler: BSONDocumentReader[Test] = Macros.reader[Test]

    def retrieveImportsCount: Future[Option[Test]] = for {
      c <- coll("imports")
      optRes <- c.find(BSONDocument("_id" -> userId.toString)).one[Test]
    } yield optRes

    userStatsDao.imprt(userId, 5).futureValue shouldEqual {}
    retrieveImportsCount.futureValue.get.imports shouldEqual 5
    userStatsDao.imprt(userId, 2).futureValue shouldEqual {}
    retrieveImportsCount.futureValue.get.imports shouldEqual 7
  }
}
