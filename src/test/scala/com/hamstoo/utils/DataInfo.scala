/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.utils

import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import com.mohiva.play.silhouette.api.LoginInfo
import com.typesafe.config.{Config, ConfigFactory}

/**
  * Trait that contain test information
  */
object DataInfo {

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // https://stackoverflow.com/questions/24153614/scala-write-value-to-typesafe-config-object
  // https://alvinalexander.com/scala/how-to-create-multiline-strings-heredoc-in-scala-cookbook
  lazy val config: Config = ConfigFactory.parseString(s"""vectors.link = "http://localhost:5000"
                                                         |idfs.resource = idfs/text8.json.zip
                                                         |mongodb.uri = "mongodb://localhost:$mongoPort/hamstoo"
                                                         |""".stripMargin)

  // these used to be `userId` and `markId` constants but were changed to functions to avoid collisions between tests
  def constructUserId(): UUID = UUID.randomUUID()
  def constructMarkId(): String = generateDbId(Mark.ID_LENGTH)

  // "val" to distinguish it from `def userId` (which has now been changed to `def constructUserId()`)
  val valUserId: UUID = constructUserId()

  val reprInfoA = ReprInfo("aReprId", ReprType.PUBLIC)
  val reprInfoPrivB = ReprInfo("bPrivReprId", ReprType.PRIVATE)
  val reprInfoPubB = ReprInfo("bPubReprId", ReprType.PUBLIC)

  val mdA = MarkData("a subject", Some("http://a.com"), Some(3.0), Some(Set("atag")), Some("a comment"))
  val mdB = MarkData("b subject", Some("http://b.com"), Some(4.0), Some(Set("btag")), Some("b comment"))
  val mA = Mark(valUserId, mark = mdA, reprs = Seq(reprInfoA))
  val mB = Mark(valUserId, mark = mdB, reprs = Seq(reprInfoPrivB, reprInfoPubB))

  val loginInfoA = LoginInfo("GProviderBookMailFace", "some_provider_key_A")
  val loginInfoB = LoginInfo("GProviderBookMailFace", "some_provider_key_B")
  val userA = User(constructUserId(), UserData(), Profile(loginInfoA, confirmed = true, Some("a@mail"), None, None, None) :: Nil)
  val userB = User(constructUserId(), UserData(), Profile(loginInfoB, confirmed = true, Some("b@mail"), None, None, None) :: Nil)

  val crazyUrl = "https://translate.google.com.ua/#ru/en/%D0%94%D0%BE%D0%B1%D1%80%D1%8B%D0%B9%20%D0%B4%D0%B5%D0%BD%D1%8C!%0A%D0%9C%D1%8B%20%D0%BE%D0%B7%D0%BD%D0%B0%D0%BA%D0%BE%D0%BC%D0%B8%D0%BB%D0%B8%D1%81%D1%8C%20%D1%81%20%D0%B2%D0%B0%D1%88%D0%B8%D0%BC%D0%B8%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F%D0%BC%D0%B8%20%D0%BD%D0%B0%20%D0%BD%D0%B0%D1%88%20%D0%BE%D1%82%D0%B7%D1%8B%D0%B2%20%20%D0%B8%20%D0%BE%D1%87%D0%B5%D0%BD%D1%8C%20%D0%BE%D0%B3%D0%BE%D1%80%D1%87%D0%B5%D0%BD%D1%8B%20%D1%82%D0%B5%D0%BC%2C%20%D1%87%D1%82%D0%BE%20%D0%B2%D0%BB%D0%B0%D0%B4%D0%B5%D0%BB%D0%B5%D1%86%20%20Bronzino%20Apartments%20%D0%BD%D0%B5%20%D1%82%D0%BE%D0%BB%D1%8C%D0%BA%D0%BE%20%D0%B0%D0%B3%D1%80%D0%B5%D1%81%D1%81%D0%B8%D0%B2%D0%B5%D0%BD%20%D0%B8%20%D0%BD%D0%B5%D1%83%D1%80%D0%B0%D0%B2%D0%BD%D0%BE%D0%B2%D0%B5%D1%88%D0%B5%D0%BD%2C%20%D0%BD%D0%BE%20%D0%B8%20%D0%BB%D0%B6%D0%B8%D0%B2.%20%D0%98%20%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%BD%D1%8B%D0%B5%20%D0%BE%D1%82%20%D0%B2%D0%B0%D1%81%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F"

  val urlHTML = "http://docs.scala-lang.org/overviews/core/futures.html"
  val urlPDF = "http://www.softwareresearch.net/fileadmin/src/docs/teaching/SS13/ST/ActorsInScala.pdf"

  val htmlWithFrames = """<html>
    <head>
      <meta http-equiv="Content-Language" content="en-us">
        <title>Apache Ant&trade; User Manual</title>
        </head>
        <frameset cols="26%,74%">
          <frame src="toc.html" name="navFrame">
          <frame src="cover.html" name="mainFrame">
          <frame src="cover.html" name="mainFrame">
        </frameset>
    </html>"""

  val emptyMarkData = MarkData("", None, None, None, None)
  def withComment(comment: String, init: MarkData = emptyMarkData): MarkData = init.copy(comment = Some(comment))
}
