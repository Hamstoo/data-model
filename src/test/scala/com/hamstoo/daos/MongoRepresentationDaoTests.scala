package com.hamstoo.daos

import com.hamstoo.models.{MarkData, Page, Representation}
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MediaType, MongoEnvironment, TestHelper}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

/**
  * MongoRepresentationDao tests.
  */
class MongoRepresentationDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with TestHelper {

  lazy val reprsDao = new MongoRepresentationDao(getDB)

  /** Create new mark. */
  def randomMarkData: MarkData = {
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val size = alpha.size

    def randStr(n:Int) = (1 to n).map(x => alpha.charAt(Random.nextInt.abs % size)).mkString
    val domain = randStr(10)
    MarkData("a subject", Some(s"http://$domain.com"))
  }

  /*"MongoRepresentaionDao" should {
    "create mark to update rep id and retrieve rep id" in new system {

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
    "* return mark if mark aleady exists and return Future[Option][None]" +
      "if mark is created on a Mark creation moment" in new system {

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

  "MongoRepresentationDao" should "save representation" in {

    // not sure if this should be here per merge or not
    //withEmbedMongoFixture() { _ =>

    //Await.result(getDB.value.get.get.collection[BSONCollection]("representations").drop(true), Duration(testDuration, MILLISECONDS))
    //Await.result(getDB.value.get.get.collection[BSONCollection]("representations").create(false), Duration(testDuration, MILLISECONDS))

    val url: Option[String] = randomMarkData.url
    //val url = "http://nymag.com/daily/intelligencer/2017/04/andrew-sullivan-why-the-reactionary-right-must-be-taken-seriously.html"
    //val url = "https://developer.chrome.com/extensions/getstarted"
    val vec: Representation.Vec = Seq(2304932.039423, 39402.3043)
    val vec2: Representation.Vec = Seq(2304932.039423, 39402.3043, 2304932.039423, 39402.3043, 2304932.039423, 39402.3043)

    val reprOrig = Representation(link = url,
      page = Page(MediaType.TEXT_HTML.toString, "sdf".getBytes),
      header = "Monday",
      doctext = "sdf",
      othtext = "sdf",
      keywords = "nothing",
        vectors = Map {"something" -> vec},
      autoGenKws = None)
    println(s"REPR ID ${reprOrig.id}, versions ${reprOrig.versions}")

    val reprCopy = reprOrig.copy(
      page = Page(MediaType.TEXT_HTML.toString, "sывфывdf".getBytes),
      header = "something",
      doctext = "sasdasdf",
      othtext = "ssadasddf",
      keywords = "something",
        vectors = Map {"month" -> vec2})

    println(s"Creating 2 representations with ids ${reprOrig.id} and ${reprCopy.id}")
    val id: String = reprsDao.save(reprOrig).futureValue /*.map(id => id)*/
    println(s"Created representation id $id")

    id should not equal null
    id should not equal ""
    Thread.sleep(2500)
    val id2: String = reprsDao.save(reprCopy).futureValue /*.map(id => id)*/
    println(s"Updated representation 2 id $id2")
    id shouldEqual id2 // this is because they have the same ID

    //val repr1 = Await.result(reprsDao retrieveById id map (repr => repr), Duration(1000, MILLISECONDS))
    //val repr2 = Await.result(reprsDao retrieveById id2 map (repr => repr), Duration(1000, MILLISECONDS))

    // use `retrieveAllById` to get both previous and updated reprs from the db
    val reprs: Seq[Representation] = reprsDao.retrieveAllById(id2).futureValue
    println(s"Print SIZE ${reprs.size}")
    reprs.foreach(r => println(s"Print Seq ${r.timeThru}"))

    reprs.size shouldEqual 2
    reprs.head.timeThru should be < Long.MaxValue
    reprs.head.timeFrom should not equal reprs.tail.head.timeFrom
    reprs.head.timeThru should be < reprs.tail.head.timeThru
  }
}
