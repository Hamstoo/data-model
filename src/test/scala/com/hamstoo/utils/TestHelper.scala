package com.hamstoo.utils

import com.hamstoo.daos._
import com.hamstoo.daos.auth.{MongoOAuth1InfoDao, MongoOAuth2InfoDao, MongoPasswordInfoDao}
import reactivemongo.api.{DefaultDB, MongoConnection, MongoDriver}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Trait that provide support method to establish connection with mongodb
  */
trait TestHelper extends DataInfo {

  lazy val statsDao = new MongoStatsDao(getDB)
  lazy val marksDao = new MongoMarksDao(getDB)
  lazy val notesDao = new MongoInlineNoteDao(getDB)
  lazy val highlightDao = new MongoHighlightDao(getDB)
  lazy val reprsDao = new MongoRepresentationDao(getDB)
  lazy val userDao = new MongoUserDao(getDB)
  lazy val auth1Dao = new MongoOAuth1InfoDao(getDB)
  lazy val auth2Dao = new MongoOAuth2InfoDao(getDB)
  lazy val passDao = new MongoPasswordInfoDao(getDB)


  def getDB: Future[DefaultDB] = {
    MongoConnection parseURI uri map MongoDriver().connection match {
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

