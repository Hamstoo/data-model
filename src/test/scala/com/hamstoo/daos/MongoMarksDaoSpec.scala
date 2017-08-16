package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import org.specs2.mutable.Specification
import play.api.mvc.Results
import reactivemongo.api.{DefaultDB, MongoConnection, MongoDriver}
import reactivemongo.bson.BSONObjectID

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}


class MongoMarksDaoSpec extends Specification {

  val link = "mongodb://localhost:27017"
  val dbName = "hamstoo"
  val timeout = Duration(2000, MILLISECONDS)

  @tailrec
  private def getDB: Future[DefaultDB] =
    MongoConnection parseURI link map MongoDriver().connection match {
      case Success(c) => c database dbName
      case Failure(e) =>
        e.printStackTrace()
        println("Failed to establish connection to MongoDB.\nRetrying...\n")
        // Logger.warn("Failed to establish connection to MongoDB.\nRetrying...\n")
        getDB
    }

  val marksDao = new MongoMarksDao(getDB)
  val reprsDao = new MongoRepresentationDao(getDB)

  "MongoMarksDao" should {

    "* findMissingReprs, both current and not" in {
      val m = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))
      Await.result(marksDao.create(m), timeout)
      Await.result(marksDao.update(m.userId, m.id, m.mark.copy(subj = "a NEW subject")), timeout)
      val missingReprMarks = Await.result(marksDao.findMissingReprs(1000000), timeout)
      missingReprMarks.count(_.userId == m.userId) mustEqual 2
    }

    "* create mark to update rep id and retrieve rep id" in {

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
          .updateMarkReprId(Set(mark.id), newReprId)
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
}
