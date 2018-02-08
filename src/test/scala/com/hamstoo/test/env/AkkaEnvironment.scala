package com.hamstoo.test.env

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import com.hamstoo.test.FlatSpecWithMatchers
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
  * This class provide test environment with embedded actor system for test purpose
  * @param actorSystemName - actor system name
  */
abstract class AkkaEnvironment(actorSystemName: String)
  extends TestKit(ActorSystem(actorSystemName))
    with FlatSpecWithMatchers
    with BeforeAndAfterAll {

  self: Suite =>

  implicit val materializer: Materializer = ActorMaterializer()

  override def afterAll(): Unit = {

    // stopping actor system
    TestKit.shutdownActorSystem(system)
  }
}

/**
  * This class provide test environment with embedded actor system and in-memory mongodb instance.
  * Actor system and MongoDB will start before all tests. MongoDB will start on default port: 12345
  * and default version mongodb:3.4.1.
  * Will clean up resources after all test was executed
  * @param actorSystemName - actor system name
  */
abstract class AkkaMongoEnvironment(actorSystemName: String)
  extends AkkaEnvironment(actorSystemName)
    with MongoEnvironment {

  self: Suite =>

  override def afterAll(): Unit = {

    // stopping actor system
    super.afterAll()

    // stopping mongodb instance
    shutdownMongo()
  }
}
