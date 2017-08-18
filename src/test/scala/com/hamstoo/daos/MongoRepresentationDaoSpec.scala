package com.hamstoo.daos

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.hamstoo.models.{Mark, MarkData, Representation}
import com.hamstoo.services.Vectorizer
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.ws.ahc.AhcWSClient
import play.api.mvc.Results
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{DefaultDB, MongoConnection, MongoDriver}
import reactivemongo.bson.BSONObjectID

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}

class MongoRepresentationDaoSpec extends Specification{

  val link = "mongodb://localhost:27017"
  val name = "hamstoo"
  val testDuration = 5000

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

  def randomMarkData = {
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val size = alpha.size

    def randStr(n:Int) = (1 to n).map(x => alpha.charAt(Random.nextInt.abs % size)).mkString
    val domain = randStr(10)
    MarkData("a subject", Some(s"http://$domain.com"))
  }


 /* "MongoRepresentaionDao" should {
    "* create mark to update rep id and retrieve rep id" in {

    val markData = randomMarkData

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


  // create new mark
  "MongoMarksDao" should {
    "* return mark if mark aleady exisists and return Future[Option][None]" +
      "if mark is created on a Mark creation moment" in {



      val markData = randomMarkData
      val mark = Mark(
        UUID.fromString(uuid),
        mark = markData,
        repIds = Some(BSONObjectID.generate.stringify :: Nil))

      //Create mark in DB
     def createMark =  marksDao.create(mark)



    val firstlyCreatedMark =   Await.result(createMark, Duration(testDuration, MILLISECONDS))
    val secondlyCreatedMark = Await.result(createMark, Duration(testDuration, MILLISECONDS))

      /**Test success conditions*/


      /**Test condition 1 */
      firstlyCreatedMark shouldNotEqual secondlyCreatedMark

      /**Test condition 2 */
      firstlyCreatedMark.isDefined shouldEqual  false

      /**Test condition 3 */
      secondlyCreatedMark.isDefined shouldEqual  true

      /**Test condition 4 */
      secondlyCreatedMark.get.mark.url shouldEqual markData.url

    }
  }*/

  "MongoRepresentaionDao" should {

    "* save representation" in {


    //    Await.result(  getDB.value.get.get.collection[BSONCollection]("representations").drop(true), Duration(testDuration, MILLISECONDS))

//ы      Await.result(  getDB.value.get.get.collection[BSONCollection]("representations").create(false), Duration(testDuration, MILLISECONDS))


      val url = randomMarkData.url
      //val url = "http://nymag.com/daily/intelligencer/2017/04/andrew-sullivan-why-the-reactionary-right-must-be-taken-seriously.html"
      //val url = "https://developer.chrome.com/extensions/getstarted"
      val vec :Representation.Vec = Seq(2304932.039423, 39402.3043)
      val vec2 :Representation.Vec = Seq(2304932.039423, 39402.3043,2304932.039423, 39402.3043,2304932.039423, 39402.3043)
      var repr: Representation = Representation(link = url,
        page = "sdf",
        header = "Monday",
        doctext = "sdf",
        othtext = "sdf",
        keywords = "nothing",
        vectors = Map{"something" -> vec})

      println("REPR ID " +repr.id)


      var reprCopy: Representation = Representation(link = url,
        page = "sывфывdf",
        header = "something",
        doctext = "sasdasdf",
        othtext = "ssadasddf",
        keywords = "something",
        vectors = Map{"month" -> vec2})

      val id: String = Await.result(reprsDao save repr map (id => id), Duration(20000, MILLISECONDS))


      println("Created 2 representations with ids " +repr.id+" "+reprCopy.id)
      println("Created representation id " +id)

      id shouldNotEqual null
      id shouldNotEqual ""
      Thread.sleep(2500)
      val id2: String = Await.result(reprsDao save reprCopy map (id => id), Duration(20000, MILLISECONDS))

      println("Created representation 2 id " +id2)

     // val repr1 = Await.result(reprsDao retrieveById  id map (repr => repr), Duration(1000, MILLISECONDS))
     //   val repr2 = Await.result(reprsDao retrieveById  id2 map (repr => repr), Duration(1000, MILLISECONDS))

      val reprs = Await.result(reprsDao retrieveAllById  id2, Duration(20000, MILLISECONDS))

      println("Print SIZE "+reprs.size)
      reprs.foreach(r => println("Print Seq "+r.timeThru))

      reprs.size shouldEqual 2

      reprs.head.timeThru should be < Long.MaxValue

      reprs.head.timeFrom shouldNotEqual  reprs.tail.head.timeFrom

      reprs.head.timeThru should be < reprs.tail.head.timeThru
    }

  }


}
