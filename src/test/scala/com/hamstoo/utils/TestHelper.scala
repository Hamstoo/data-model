package com.hamstoo.utils

import com.hamstoo.daos._
import com.hamstoo.daos.auth.{MongoOAuth1InfoDao, MongoOAuth2InfoDao, MongoPasswordInfoDao}
import reactivemongo.api.DefaultDB

import scala.concurrent.Future

/**
  * Trait that provide support method to establish connection with mongodb
  */
trait TestHelper extends DataInfo {

  lazy val statsDao = new MongoUserStatsDao(getDB)
  lazy val marksDao = new MongoMarksDao(getDB)
  lazy val notesDao = new MongoInlineNoteDao(getDB)
  lazy val highlightDao = new MongoHighlightDao(getDB)
  lazy val reprsDao = new MongoRepresentationDao(getDB)
  lazy val userDao = new MongoUserDao(getDB)
  lazy val auth1Dao = new MongoOAuth1InfoDao(getDB)
  lazy val auth2Dao = new MongoOAuth2InfoDao(getDB)
  lazy val passDao = new MongoPasswordInfoDao(getDB)

  def getDB: Future[DefaultDB] = com.hamstoo.utils.getDB(uri, dbName)
}

