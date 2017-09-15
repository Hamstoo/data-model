package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import com.hamstoo.specUtils
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Await
import scala.concurrent.duration._


class MongoMarksDaoSpec extends Specification {

  "MongoMarksDao" should {

    "* findMissingReprs, both current and not" in new system {
      val m = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))
      Await.result(marksDao.insert(m), timeout)
      Await.result(marksDao.update1(m.userId, m.id, m.mark.copy(subj = "a NEW subject")), timeout)
      val missingReprMarks: Seq[Mark] = Await.result(marksDao.findMissingReprs(1000000), timeout)
      missingReprMarks.count(_.userId == m.userId) mustEqual 2
    }
  }


  // https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala
  trait system extends Scope {
    val marksDao: MongoMarksDao = specUtils.marksDao
    val reprsDao: MongoRepresentationDao = specUtils.reprsDao
    val timeout: Duration = specUtils.timeout
  }
}
