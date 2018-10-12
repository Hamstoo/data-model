/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.hamstoo.models.{MarkData, Representation}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils._

import scala.util.Random

/**
  * MongoRepresentationDao tests.
  */
class RepresentationDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler {

  /** Create new mark. */
  def randomMarkData: MarkData = {
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val size = alpha.length

    def randStr(n:Int) = (1 to n).map(x => alpha.charAt(Random.nextInt.abs % size)).mkString
    val domain = randStr(10)
    MarkData("a subject", Some(s"http://$domain.com"))
  }

  val repr = Representation(
    link = None,
    header = None,
    doctext = "doctext",
    othtext = None,
    keywords = None,
    vectors = Map.empty[String, Representation.Vec],
    autoGenKws = None)

  "MongoRepresentationDao" should "(UNIT) save representation" in {

    val url: Option[String] = randomMarkData.url
    //val url = "http://nymag.com/daily/intelligencer/2017/04/andrew-sullivan-why-the-reactionary-right-must-be-taken-seriously.html"
    //val url = "https://developer.chrome.com/extensions/getstarted"
    val vec: Representation.Vec = Seq(2304932.039423, 39402.3043)
    val vec2: Representation.Vec = Seq(2304932.039423, 39402.3043, 2304932.039423, 39402.3043, 2304932.039423, 39402.3043)

    val reprOrig = Representation(link = url,
      header = None,
      doctext = "sdf",
      othtext = None,
      keywords = None,
      vectors = Map("something" -> vec),
      autoGenKws = None)
    println(s"REPR ID ${reprOrig.id}, versions ${reprOrig.versions}")

    val reprCopy = reprOrig.copy(
      header = None,
      doctext = "sasdasdf",
      othtext = None,
      keywords = None,
      vectors = Map("month" -> vec2))

    // save representation
    println(s"Creating 2 representations with IDs ${reprOrig.id} [${reprOrig.timeFrom}] and ${reprCopy.id} [${reprCopy.timeFrom}]")
    val id: String = reprsDao.save1(reprOrig).futureValue
    println(s"Created representation ID $id")

    id should not equal null
    id should not equal ""
    id shouldEqual reprOrig.id

    // saves representation copy
    val id2: String = reprsDao.save1(reprCopy.copy(timeFrom = TIME_NOW)).futureValue
    println(s"Updated representation 2 ID $id2")
    id shouldEqual id2

    // use `retrieveAll` to get both previous and updated reprs from the db
    val reprs: Seq[Representation] = reprsDao.retrieveAll(id2).futureValue
    println(s"Print SIZE ${reprs.size}")
    reprs.foreach(r => println(s"Print Seq ${r.timeThru}"))

    // reprs must be sorted and 1st repr must be actual
    reprs.size shouldEqual 2
    reprs.head.timeFrom should be < reprs.head.timeThru
    reprs.head.timeThru shouldEqual reprs.last.timeFrom
    reprs.last.timeThru shouldEqual INF_TIME
  }

  it should "(UNIT) insert representations" in {
    reprsDao.insert(repr).futureValue shouldEqual repr
  }

  it should "(UNIT) retrieve representatons" in {
    reprsDao.retrieve(repr.id).futureValue.get shouldEqual repr
  }

  it should "(UNIT) update representation" in {
    val repr2 = repr.copy(link = Some("link"), timeFrom = TIME_NOW)
    reprsDao.update1(repr2).futureValue shouldEqual repr2
  }

  it should "(UNIT) retrieve all by id" in {
    reprsDao.retrieveAll(repr.id).futureValue.size shouldEqual 2
  }
}
