/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.test.env

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import com.hamstoo.daos._
import com.hamstoo.daos.auth.{OAuth1InfoDao, OAuth2InfoDao, PasswordInfoDao}
import com.hamstoo.services.HighlightsIntersectionService
import com.hamstoo.utils.{DataInfo, getDbConnection}
import de.flapdoodle.embed.mongo.distribution.Version
import org.scalatest.{BeforeAndAfterAll, Suite}
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{DefaultDB, MongoConnection}

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.Future

/**
  * Trait that provide test environment with in-memory mongodb instance for test purpose.
  * MongoDB instance will start before all tests on default port: 12345
  * and default version: mongodb:3.4.1.
  * Will clean up resources after all test was executed
  */
trait MongoEnvironment extends MongoEmbedDatabase with BeforeAndAfterAll {

  self: Suite =>

  // default mongo port, override if needed
  def mongoPort: Int = 12345

  // default mongo version, override if needed
  def mongoVersion: Version = Version.V3_5_1

  // mongodb uri and database name
  def dbUri = s"mongodb://localhost:$mongoPort/hamstoo"

  // fongo (fake mongo) instance
  lazy val fongo: MongodProps = mongoStart(mongoPort, mongoVersion)

  override def beforeAll(): Unit = {
    println(s"Starting MongoDB:$mongoVersion instance on port: ${DataInfo.mongoPort}")
    fongo // start fake mongodb
    Thread.sleep(1000) // delay to successful start
    dbConn = Some(getDbConnection(DataInfo.config.getString("mongodb.uri")))
  }

  override def afterAll(): Unit = shutdownMongo()

  def shutdownMongo(): Unit = {
    println("Stopping MongoDB instance")
    mongoStop(fongo) // stop fake mongodb
    Thread.sleep(1000) // delay to successful stop
    dbConn = None
  }

  // this should only happen once (per mongodb instance) but since we start a new instance every time
  // MongoEnvironment is extended (by nature of MongoEnvironment being a trait and not an object) it gets
  // called multiple times during testing
  var dbConn: Option[(MongoConnection, String)] = None // made this a var to prevent usage of it before `beforeAll` is called

  // this (lightweight) function is called every time a DAO method is invoked
  lazy implicit val db: () => Future[DefaultDB] = () => {
    if (dbConn.isEmpty)
      throw new Exception("dbConn cannot be used before beforeAll is called")
    dbConn.get._1.database(dbConn.get._2)
  }

  // for defining custom query, only for tests purpose
  def coll(name: String): Future[BSONCollection] = db().map(_ collection name)

  // could instead use an injector here, but implementing that would involve work, and this code would look the same
  lazy val userStatsDao = new UserStatDao
  lazy implicit val userDao = new UserDao
  lazy implicit val urlDuplicatesDao = new UrlDuplicateDao
  lazy implicit val marksDao = new MarkDao
  lazy implicit val pagesDao = new PageDao
  lazy implicit val hlightsDao = new HighlightDao
  lazy val notesDao = new InlineNoteDao
  lazy val reprsDao = new RepresentationDao
  lazy val eratingsDao = new ExpectedRatingDao
  lazy val vectorsDao = new WordVectorDao
  lazy val auth1Dao = new OAuth1InfoDao
  lazy val auth2Dao = new OAuth2InfoDao
  lazy val passDao = new PasswordInfoDao
  lazy val searchDao = new SearchStatDao
  lazy val tokenDao = new UserTokenDao
  lazy val hlIntersectionSvc = new HighlightsIntersectionService
  lazy val userSuggDao = new UserSuggestionDao
}
