package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, Stats}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.TestHelper
import org.scalatest.OptionValues
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoStatsDaoTests extends FlatSpecWithMatchers
  with MongoEnvironment
  with OptionValues
  with FutureHandler
  with TestHelper {

  lazy val statsDao = new MongoStatsDao(getDB)
  lazy val marksDao = new MongoMarksDao(getDB)

  val usrId: UUID = UUID.randomUUID()
  val tagSet = Some(Set("tag1, tag2"))
  val cmt = Some("Query")
  val pubRepr = Some("repr")
  val newMarkData = MarkData("a NEW subject1", Some("https://github.com"))
  val m1 = Mark(usrId, mark =  MarkData("a subject342", Some("http://hamstoo.com"), tags = tagSet, comment = cmt))
  val m2 = Mark(usrId, mark = MarkData("a subject1", Some("http://hamstoo2.com"), tags = tagSet), pubRepr = pubRepr)


  "MongoHighlightDao" should "(UNIT) insert highlights" in {

   val totalMarks: Future[Stats] = for {
     mi1 <- marksDao.insert(m1)
     mi2 <- marksDao.insert(m2)
     totalMarks <- statsDao.stats(usrId, 0)
    } yield {
      totalMarks
    }
    totalMarks.futureValue.marks shouldEqual 2
  }

}
