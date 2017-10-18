package com.hamstoo.test.env

import com.github.simplyscala.{MongoEmbedDatabase, MongodProps}
import com.typesafe.scalalogging.Logger
import de.flapdoodle.embed.mongo.distribution.Version
import org.scalatest.{BeforeAndAfterAll, Suite}

trait MongoEnvironment extends MongoEmbedDatabase with BeforeAndAfterAll {

  self: Suite =>

  val log: Logger = Logger(classOf[MongoEnvironment])
  // default mongo version, override if needed
  val mongoVersion: Version = Version.V3_4_1

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // uninitialized fongo(fake mongo) instance
  var fongo: MongodProps = _

  abstract override def beforeAll(): Unit = {
    log.info(s"Starting Fake MongoDB:$mongoVersion instance on port:$mongoPort")
    fongo = mongoStart(mongoPort, mongoVersion)
  }

  abstract override def afterAll(): Unit = {
    log.info(s"Stopping Fake MongoDB instance on port:$mongoPort")
    mongoStop(fongo)
  }
}
