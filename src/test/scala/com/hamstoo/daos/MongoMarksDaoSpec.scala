package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import com.hamstoo.specUtils
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.mvc.Results
import reactivemongo.bson.BSONObjectID

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Try


class MongoMarksDaoSpec extends Specification {

  "MongoMarksDao" should {

    "* findMissingReprs, both current and not" in new system {
      val m = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))
      Await.result(marksDao.create(m), timeout)
      Await.result(marksDao.update(m.userId, m.id, m.mark.copy(subj = "a NEW subject")), timeout)
      val missingReprMarks = Await.result(marksDao.findMissingReprs(1000000), timeout)
      missingReprMarks.count(_.userId == m.userId) mustEqual 2
    }

    "* create mark to update rep id and retrieve rep id" in new system {

      // create new mark
      val userId = UUID.fromString("8da651bb-1a17-4144-bc33-c176e2ccf8a0")
      println(userId.toString)
      val markData = MarkData("a subject", Some("http://hamstoo.com"))
      val mark = Mark(userId, mark = markData, repIds = Some(BSONObjectID.generate.stringify :: Nil))

      // Create mark in DB
      Try {
        Await.result(marksDao.create(mark), timeout)
      } map println
      //  Results.Accepted mustEqual (resultCreateMark)
      Thread.sleep(timeout.toMillis)

      // Retrieve marks
      val marks = Await.result(marksDao.receive(userId), timeout)
      marks.foreach(mark => mark.repIds.foreach(println))
      val markIdToUpdate = marks.last.id
      println("Last mark id to update " + markIdToUpdate)

      val id = marks.head.repIds.get.last
      println("Last repr id to update " + id)

      val createdRepresentation = Await.result(reprsDao.retrieveById(id), timeout)

      val newReprId = BSONObjectID.generate.stringify
      println("newReprId to be recorded " + newReprId)

      // Update Mark
      val resultUpdateReprIdOfMark = Await.result(
        marksDao
          .updateMarkReprId(mark.id, mark.timeFrom, newReprId)
          .map(_ => Results.Accepted),
        timeout)
      println(resultUpdateReprIdOfMark)
      //   Results.Accepted mustEqual(resultUpdateReprIdOfMark)

      // Retrieve update mark
      val repIds: Seq[String] =
        Await.result(marksDao.receive(userId, mark.id), timeout).get.repIds.get
      repIds.foreach(x => println("ID new " + x))
      val getUodatedReprIdOfMark = repIds.last
      repIds.contains(newReprId) mustEqual true
      newReprId mustEqual getUodatedReprIdOfMark

      /* val updatedRepresentation = Await.result(reprsDao.retrieveById(getUodatedReprIdOfMark),
        Duration(timeout, MILLISECONDS))
      updatedRepresentation.get.timeThru should be > createdRepresentation.get.timeThru*/
    }
  }


  // https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala
  trait system extends Scope {
    val marksDao: MongoMarksDao = specUtils.marksDao
    val reprsDao: MongoRepresentationDao = specUtils.reprsDao
    val timeout: Duration = specUtils.timeout
  }
}
