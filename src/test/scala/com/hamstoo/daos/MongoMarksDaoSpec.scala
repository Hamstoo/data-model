package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, Representation}
import com.hamstoo.specUtils
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.mvc
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
      val missingReprMarks: Seq[Mark] = Await.result(marksDao.findMissingReprs(1000000), timeout)
      missingReprMarks.count(_.userId == m.userId) mustEqual 2
    }

    "* create mark to update rep id and retrieve rep id" in new system {

      // create new mark
      val userId: UUID = UUID.fromString("8da651bb-1a17-4144-bc33-c176e2ccf8a0")
      println(userId.toString)
      val markData = MarkData("a subject", Some("http://hamstoo.com"))
      val mark = Mark(userId, mark = markData, pubRepr = Some(BSONObjectID.generate.stringify))

      // Create mark in DB
      Try {
        Await.result(marksDao.create(mark), timeout)
      } map println
      //  Results.Accepted mustEqual (resultCreateMark)
      Thread.sleep(timeout.toMillis)

      // Retrieve marks
      val marks: Seq[Mark] = Await.result(marksDao.receive(userId), timeout)
      marks.foreach(mark => mark.pubRepr.foreach(println))
      val markIdToUpdate: String = marks.last.id
      println("Last mark id to update " + markIdToUpdate)

      val id: String = marks.head.pubRepr.get
      println("Repr id to update " + id)

      val createdRepresentation: Option[Representation] = Await.result(reprsDao.retrieveById(id), timeout)

      val newReprId: String = BSONObjectID.generate.stringify
      println("newReprId to be recorded " + newReprId)

      // Update Mark
      val resultUpdateReprIdOfMark: mvc.Results.Status = Await.result(
        marksDao
          .updatePublicReprId(mark.id, mark.timeFrom, newReprId)
          .map(_ => Results.Accepted),
        timeout)
      println(resultUpdateReprIdOfMark)
      //   Results.Accepted mustEqual(resultUpdateReprIdOfMark)

      // Retrieve update mark
      val repId: String =
        Await.result(marksDao.receive(userId, mark.id), timeout).get.pubRepr.get
      println("ID new " + repId)
      repId === newReprId

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
