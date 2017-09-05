package com.hamstoo

import com.hamstoo.daos.{MongoCommentDao, MongoMarksDao, MongoRepresentationDao}
import com.hamstoo.utils.TestHelper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.higherKinds


/**
  * Utilities that may be used by lots of different tests.
  * Much of this code is taken from repr-engine's main.Main module.
  */
package object specUtils extends TestHelper {

  val link = "mongodb://127.0.0.1:27017"
  val dbName = "hamstoo"
  val timeout: Duration = 2000 milliseconds
//
//  @tailrec
//  private def getDB: Future[DefaultDB] = {
//    MongoConnection parseURI link map MongoDriver().connection match {
//      case Success(c) =>
//        println(s"Successfully connected to ${c.options}")
//        c database dbName
//      case Failure(e) =>
//        e.printStackTrace()
//        println("Failed to establish connection to MongoDB.\nRetrying...\n")
//        // Logger.warn("Failed to establish connection to MongoDB.\nRetrying...\n")
//        getDB
//    }
//  }
//
  lazy val marksDao = new MongoMarksDao(getDB)
  lazy val reprsDao = new MongoRepresentationDao(getDB)
  lazy val commentDao = new MongoCommentDao(getDB)
}
