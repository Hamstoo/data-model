package com.hamstoo.utils

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import de.flapdoodle.embed.mongo.distribution.Version
import org.scalatest.{BeforeAndAfterAll, Suite}
import play.api.Logger

/**
  * Trait that start Fake MongoDB instance
  */
trait MongoEnvironment extends MongoEmbedDatabase with BeforeAndAfterAll {
  self: Suite =>

  // default mongo version, override if needed
  val mongoVersion: Version = Version.V3_4_1

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // uninitialized fongo(fake mongo) instance
  var fongo: MongodProps = _

  override protected def beforeAll(): Unit = {
    Logger.info(s"Starting Fake MongoDB:$mongoVersion instance on port:$mongoPort")
    fongo = mongoStart(mongoPort, mongoVersion)
  }

  override protected def afterAll(): Unit = {
    Logger.info(s"Stopping Fake MongoDB instance on port:$mongoPort")
    mongoStop(fongo)
  }
}
