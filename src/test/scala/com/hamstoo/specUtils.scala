package com.hamstoo

import java.util.UUID

import com.hamstoo.daos._
import com.hamstoo.models.{Mark, MarkData}
import reactivemongo.api._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{Failure, Success}


/**
  * Utilities that may be used by lots of different tests.
  * Much of this code is taken from repr-engine's main.Main module.
  */
package object specUtils {

  val vectorsLink = "http://localhost:5000"
  val idfsResource = "idfs/text8.json.zip"

  val link = "mongodb://localhost:27017"
  val dbName = "hamstoo"
  val timeout: Duration = 5 seconds

  @tailrec
  def getDB: Future[DefaultDB] =
    MongoConnection parseURI link map MongoDriver().connection match {
      case Success(c) => c database dbName
      case Failure(e) =>
        e.printStackTrace()
        println("Failed to establish connection to MongoDB.\nRetrying...\n")
        // Logger.warn("Failed to establish connection to MongoDB.\nRetrying...\n")
        getDB
    }

  lazy val marksDao = new MongoMarksDao(getDB)
  lazy val reprsDao = new MongoRepresentationDao(getDB)
  lazy val commentDao = new MongoCommentDao(getDB)
  lazy val highlightDao = new MongoHighlightDao(getDB)
  lazy val vecDao = new MongoVectorsDao(getDB)

  val userId: UUID = UUID.randomUUID
  val mdA = MarkData("a subject", Some("http://a.com"), Some(3.0), Some(Set("atag")), Some("a comment"))
  val mdB = MarkData("b subject", Some("http://b.com"), Some(4.0), Some(Set("btag")), Some("b comment"))
  val mA = Mark(userId, mark = mdA, pubRepr = Some("aPubRepr"), privRepr = Some("aPrivRepr"))
  val mB = Mark(userId, mark = mdB, pubRepr = Some("bPubRepr"), privRepr = Some("bPrivRepr"))
}
