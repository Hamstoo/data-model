package com.hamstoo.daos

import com.hamstoo.models.{MarkData, Page, Representation}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.MediaType

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

/**
  * MongoRepresentationDao tests.
  */
class MongoRepresentationDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler {

  import com.hamstoo.utils.DataInfo._

  /** Create new mark. */
  def randomMarkData: MarkData = {
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val size = alpha.length

    def randStr(n:Int) = (1 to n).map(x => alpha.charAt(Random.nextInt.abs % size)).mkString
    val domain = randStr(10)
    MarkData("a subject", Some(s"http://$domain.com"))
  }

  "MongoRepresentationDao" should "(UNIT) save representation" in {

    val url: Option[String] = randomMarkData.url
    //val url = "http://nymag.com/daily/intelligencer/2017/04/andrew-sullivan-why-the-reactionary-right-must-be-taken-seriously.html"
    //val url = "https://developer.chrome.com/extensions/getstarted"
    val vec: Representation.Vec = Seq(2304932.039423, 39402.3043)
    val vec2: Representation.Vec = Seq(2304932.039423, 39402.3043, 2304932.039423, 39402.3043, 2304932.039423, 39402.3043)

    val reprOrig = Representation(link = url,
      page = Some(Page(MediaType.TEXT_HTML.toString, "sdf".getBytes)),
      header = Some("Monday"),
      doctext = "sdf",
      othtext = Some("sdf"),
      keywords = Some("nothing"),
        vectors = Map {"something" -> vec},
      autoGenKws = None)
    println(s"REPR ID ${reprOrig.id}, versions ${reprOrig.versions}")

    val reprCopy = reprOrig.copy(
      page = Some(Page(MediaType.TEXT_HTML.toString, "sывфывdf".getBytes)),
      header = Some("something"),
      doctext = "sasdasdf",
      othtext = Some("ssadasddf"),
      keywords = Some("something"),
        vectors = Map {"month" -> vec2})

    // save representation
    println(s"Creating 2 representations with ids ${reprOrig.id} and ${reprCopy.id}")
    val id: String = reprsDao.save(reprOrig).futureValue /*.map(id => id)*/
    println(s"Created representation id $id")

    id should not equal null
    id should not equal ""
    Thread.sleep(2500)

    // saves representation copy
    val id2: String = reprsDao.save(reprCopy).futureValue /*.map(id => id)*/
    println(s"Updated representation 2 id $id2")
    id shouldEqual id2 // this is because they have the same ID

    // use `retrieveAllById` to get both previous and updated reprs from the db
    val reprs: Seq[Representation] = reprsDao.retrieveAllById(id2).futureValue
    println(s"Print SIZE ${reprs.size}")
    reprs.foreach(r => println(s"Print Seq ${r.timeThru}"))

    // reprs must be sorted and 1st repr must be actual
    reprs.size shouldEqual 2
    reprs.head.timeThru should be < com.hamstoo.utils.INF_TIME
    reprs.head.timeFrom should not equal reprs.tail.head.timeFrom
    reprs.head.timeThru should be < reprs.tail.head.timeThru
  }
}
