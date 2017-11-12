package com.hamstoo.test.env

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import com.hamstoo.daos._
import com.hamstoo.daos.auth.{MongoOAuth1InfoDao, MongoOAuth2InfoDao, MongoPasswordInfoDao}
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

  // uninitialized fongo(fake mongo) instance
  final var fongo: MongodProps = _

  override def beforeAll(): Unit = {

    println(s"Starting MongoDB:$mongoVersion instance on port: $mongoPort")
    // starting fake mongodb instance
    fongo = mongoStart(mongoPort, mongoVersion)

    // delay to successful start
    Thread.sleep(1000)
  }

  override def afterAll(): Unit = {

    println("Stopping MongoDB instance")
    // stopping fake mongodb instance
    mongoStop(fongo)

    // delay to successful stop
    Thread.sleep(1000)
  }

  // this should only happen once (per mongodb instance) but since we start a new instance every time
  // MongoEnvironment is extended (by nature of MongoEnvironment being a trait and not an object) it gets
  // called multiple times during testing
  lazy val dbConn: MongoConnection = getDbConnection(dbUri)._2

  // this (lightweight) function is called every time a DAO method is invoked
  lazy val db: () => Future[DefaultDB] = () => dbConn.database(dbName)

  // for defining custom query, only for tests purpose
  def coll(name: String): Future[BSONCollection] = db().map(_ collection name)


  lazy val statsDao = new MongoUserStatsDao(db)
  lazy val marksDao = new MongoMarksDao(db)
  lazy val notesDao = new MongoInlineNoteDao(db)
  lazy val highlightDao = new MongoHighlightDao(db)
  lazy val reprsDao = new MongoRepresentationDao(db)
  lazy val vectorsDao = new MongoVectorsDao(db)
  lazy val userDao = new MongoUserDao(db)
  lazy val auth1Dao = new MongoOAuth1InfoDao(db)
  lazy val auth2Dao = new MongoOAuth2InfoDao(db)
  lazy val passDao = new MongoPasswordInfoDao(db)
  lazy val searchDao = new MongoSearchStatsDao(db)
  lazy val tokenDao = new MongoUserTokenDao(db)
}

object MongoEnvironment {

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // mongodb uri and database name
  lazy val dbUri = s"mongodb://localhost:$mongoPort"
  lazy val dbName = "hamstoo"
}
