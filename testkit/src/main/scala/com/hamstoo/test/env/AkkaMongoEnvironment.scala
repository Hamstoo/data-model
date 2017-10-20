package com.hamstoo.test.env

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.hamstoo.test.FlatSpecWithMatchers
import org.scalatest.Suite

/**
  * This class provide test environment with embedded actor system and in-memory mongodb instance.
  * Actor system and MongoDB will start before all tests. MongoDB will start on default port: 12345
  * and default version mongodb:3.4.1.
  * Will clean up resources after all test was executed
  * @param actorSystemName - actor system name
  */
abstract class AkkaMongoEnvironment(actorSystemName: String)
  extends TestKit(ActorSystem(actorSystemName))
    with FlatSpecWithMatchers
    with MongoEnvironment {

  self: Suite =>

  override def afterAll(): Unit = {

    // stopping actor system
    TestKit.shutdownActorSystem(system)

    // stopping mongodb instance
    super.afterAll()
  }

}
