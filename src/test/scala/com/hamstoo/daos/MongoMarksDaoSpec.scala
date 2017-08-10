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
  val name = "hamstoo"
  val testDuration = 2000

  @tailrec
  private def getDB: Future[DefaultDB] =
    MongoConnection parseURI link map MongoDriver().connection match {
      case Success(c) => c database name
      case Failure(e) =>
        e.printStackTrace()
        println("Failed to establish connection to MongoDB.\nRetrying...\n")
        // Logger.warn("Failed to establish connection to MongoDB.\nRetrying...\n")
        getDB
    }

  val marksDao = new MongoMarksDao(getDB)
  val reprsDao = new MongoRepresentationDao(getDB)
  val uuid = "8da651bb-1a17-4144-bc33-c176e2ccf8a0"

  println(uuid.toString)
  val markUuid: UUID = UUID.randomUUID

  // create new mark
  "MongoMarksDao" should {
    "* create mark to update rep id and retrieve rep id" in {

      val markData = MarkData("a subject", Some("http://hamstoo.com"))
      val mark = Mark(
        UUID.fromString(uuid),
        mark = markData,
        repIds = Some(BSONObjectID.generate.stringify :: Nil))

      //Create mark in DB
      Try {
        Await.result(
          marksDao.create(mark),
          Duration(testDuration, MILLISECONDS))
      } map println
      //  Results.Accepted mustEqual (resultCreateMark)
      Thread.sleep(testDuration)

      //Retrive marks
      val marks = Await.result(
        marksDao.receive(UUID.fromString(uuid)),
        Duration(testDuration, MILLISECONDS))
      marks.foreach(mark => mark.repIds.foreach(println))
      val markIdToUpdate = marks.last.id
      println("Last mark id to update " + markIdToUpdate)

      val id = marks.head.repIds.get.last
      println("Last repr id to update " + id)

      val createdRepresentation = Await.result(
        reprsDao.retrieveById(id),
        Duration(testDuration, MILLISECONDS))

      val newReprId = BSONObjectID.generate.stringify
      println("newReprId to be recorded " + newReprId)
      //Update Mark
      val resultUpdateReprIdOfMark = Await.result(
        marksDao
          .updateMarkReprId(Set(mark.id), newReprId)
          .map(_ => Results.Accepted),
        Duration(testDuration, MILLISECONDS))
      println(resultUpdateReprIdOfMark)
      //   Results.Accepted mustEqual(resultUpdateReprIdOfMark)

      //Retrieve update mark
      val repIds: Seq[String] =
        Await.result(
          marksDao.receive(UUID.fromString(uuid), mark.id),
          Duration(testDuration, MILLISECONDS))
          .get
          .repIds
          .get
      repIds.foreach(x => println("ID new " + x))
      val getUodatedReprIdOfMark = repIds.last
      repIds.contains(newReprId) mustEqual true
      newReprId mustEqual getUodatedReprIdOfMark

      /* val updatedRepresentation = Await.result(reprsDao.retrieveById(getUodatedReprIdOfMark),
        Duration(testDuration, MILLISECONDS))
      updatedRepresentation.get.timeThru should be > createdRepresentation.get.timeThru*/

    }
  }
}
