package com.hamstoo.utils

import com.hamstoo.specUtils._
import reactivemongo.api.{DefaultDB, MongoConnection, MongoDriver}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

// https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala
trait TestHelper {
  def getDB: Future[DefaultDB] = {
    MongoConnection parseURI link map MongoDriver().connection match {
      case Success(c) =>
        println(s"Successfully connected to ${c.options}")
        c database dbName
      case Failure(e) =>
        e.printStackTrace()
        println("Failed to establish connection to MongoDB.\nRetrying...\n")
        // Logger.warn("Failed to establish connection to MongoDB.\nRetrying...\n")
        getDB
    }
  }
}

