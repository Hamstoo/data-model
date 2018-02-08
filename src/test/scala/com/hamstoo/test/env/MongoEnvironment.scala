package com.hamstoo.test.env

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import com.hamstoo.daos._
import com.hamstoo.daos.auth.{MongoOAuth1InfoDao, MongoOAuth2InfoDao, MongoPasswordInfoDao}
import com.hamstoo.services.HighlightsIntersectionService
import com.hamstoo.utils.getDbConnection
import de.flapdoodle.embed.mongo.distribution.Version
import org.scalatest.{BeforeAndAfterAll, Suite}
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.{DefaultDB, MongoConnection}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Trait that provide test environment with in-memory mongodb instance for test purpose.
  * MongoDB instance will start before all tests on default port: 12345
  * and default version: mongodb:3.4.1.
  * Will clean up resources after all test was executed
  */
trait MongoEnvironment extends MongoEmbedDatabase with BeforeAndAfterAll {

  self: Suite =>

  import MongoEnvironment._

  // default mongo version, override if needed
  val mongoVersion: Version = Version.V3_4_1

  // uninitialized fongo (fake mongo) instance
  final var fongo: MongodProps = _

  override def beforeAll(): Unit = {
    println(s"Starting MongoDB:$mongoVersion instance on port: $mongoPort")
    fongo = mongoStart(mongoPort, mongoVersion)
    Thread.sleep(1000) // delay to successful start
    dbConn = Some(getDbConnection(dbUri))
  }

  override def afterAll(): Unit = shutdownMongo()

  def shutdownMongo(): Unit = {
    println("Stopping MongoDB instance")
    mongoStop(fongo)
    Thread.sleep(1000) // delay to successful stop
    dbConn = None
  }

  // this should only happen once (per mongodb instance) but since we start a new instance every time
  // MongoEnvironment is extended (by nature of MongoEnvironment being a trait and not an object) it gets
  // called multiple times during testing
  var dbConn: Option[(MongoConnection, String)] = None // made this a var to prevent usage of it before `beforeAll` is called

  // this (lightweight) function is called every time a DAO method is invoked
  lazy val db: () => Future[DefaultDB] = () => {
    if (dbConn.isEmpty)
      throw new Exception("dbConn cannot be used before beforeAll is called")
    dbConn.get._1.database(dbConn.get._2)
  }

  // for defining custom query, only for tests purpose
  def coll(name: String): Future[BSONCollection] = db().map(_ collection name)


  lazy val statsDao = new MongoUserStatsDao(db)
  lazy implicit val userDao = new MongoUserDao(db)
  lazy implicit val marksDao = new MongoMarksDao(db)
  lazy implicit val pagesDao = new MongoPagesDao(db)
  lazy val notesDao = new MongoInlineNoteDao(db)
  lazy val hlightsDao = new MongoHighlightDao(db)
  lazy val reprsDao = new MongoRepresentationDao(db)
  lazy val eratingsDao = new MongoExpectedRatingDao(db)
  lazy val vectorsDao = new MongoVectorsDao(db)
  lazy val auth1Dao = new MongoOAuth1InfoDao(db)
  lazy val auth2Dao = new MongoOAuth2InfoDao(db)
  lazy val passDao = new MongoPasswordInfoDao(db)
  lazy val searchDao = new MongoSearchStatsDao(db)
  lazy val tokenDao = new MongoUserTokenDao(db)
  lazy val hlIntersectionSvc = new HighlightsIntersectionService(hlightsDao)
}

object MongoEnvironment {

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // mongodb uri and database name
  lazy val dbUri = s"mongodb://localhost:$mongoPort/hamstoo"
}
