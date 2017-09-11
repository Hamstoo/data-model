package com.hamstoo

import com.hamstoo.daos._
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
  val timeout: Duration = 5000 milliseconds

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
}
