package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, UserStats}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * This class provides unit tests for MongoUserStatsDao.
  * 2 tests in this class are responsible for testing total marks particular users.
  * They are designed to test the counting of marks after marks modifications operations.
  */
class MongoUserStatsDaoTests extends FlatSpecWithMatchers
    with MongoEnvironment
    with OptionValues
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  // construct a new userId for these tests alone
  val userId: UUID = constructUserId()

  val tagSet = Some(Set("tag1asdasda, tag2adasd"))
  val cmt = Some("Queryasdasd")
  val pubRepr = Some("reprasdsad")
  val newMarkData = MarkData("a NEW subjфывыфвect1", Some("https://github.com"))
  val m1 = Mark(userId, mark =  MarkData("a subjasdect342", Some("http://hamstoo231321.com"), tags = tagSet, comment = cmt))
  val m2 = Mark(userId, mark = MarkData("a subasdject1", Some("http://hamstoo223.com"), tags = tagSet), pubRepr = pubRepr)
  val m3 = Mark(userId, mark = MarkData("a subasdject1asdasd", Some("http://hamstooasdasd223.com"), tags = tagSet), pubRepr = pubRepr)

  /** This test is designed to create two marks for user. */
  "MongoUserStatsDao" should "(UNIT) calculate marks inserted by userId" in {

    val totalMarks: Future[UserStats] =
      for {
        mi1 <- marksDao.insert(m1)
        mi2 <- marksDao.insert(m2)
        totalMarks <- statsDao.stats(userId, 0)
      } yield totalMarks

      totalMarks.futureValue.marks shouldEqual 2
  }

  /** This test is designed to modify marks, but to keep only two actual marks for user. */
  it should "(UNIT) calculate marks updated by userId" in {

    val totalMarks: Future[UserStats] =
      for {
        mi1 <- marksDao.update(userId, m1.id, m2.mark)
        mi2 <- marksDao.update(userId, m2.id, m1.mark)
        mi3 <- marksDao.update(userId, m1.id, m1.mark)
        mi4 <- marksDao.update(userId, m2.id, m2.mark)
        mi5 <- marksDao.insert(m3)
        intResult <- marksDao.delete(userId, m2.id :: Nil)
        totalMarks <- statsDao.stats(userId, 0)
      } yield totalMarks

    totalMarks.futureValue.marks shouldEqual 2
  }

  // TODO tests for: punch, import
}
