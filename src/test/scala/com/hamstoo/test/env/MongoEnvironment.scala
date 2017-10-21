package com.hamstoo.test.env

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import de.flapdoodle.embed.mongo.distribution.Version
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
  * Trait that provide test environment with in-memory mongodb instance for test purpose.
  * MongoDB instance will start before all tests on default port: 12345
  * and default version: mongodb:3.4.1.
  * Will clean up resources after all test was executed
  */
trait MongoEnvironment extends MongoEmbedDatabase with BeforeAndAfterAll {

  self: Suite =>

  // default mongo version, override if needed
  val mongoVersion: Version = Version.V3_4_1

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // uninitialized fongo(fake mongo) instance
  final var fongo: MongodProps = _

  override def beforeAll(): Unit = {
    // starting fake mongodb instance
    fongo = mongoStart(mongoPort, mongoVersion)
  }

  override def afterAll(): Unit = {
    // stopping fake mongodb instance
    mongoStop(fongo)
  }
}
