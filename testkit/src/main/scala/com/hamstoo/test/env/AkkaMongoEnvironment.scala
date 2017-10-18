package com.hamstoo.test.env

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.hamstoo.test.FlatSpecWithMatchers
import org.scalatest.Suite

abstract class AkkaMongoEnvironment(actorSystemName: String)
  extends TestKit(ActorSystem(actorSystemName))
    with FlatSpecWithMatchers
    with MongoEnvironment {

  self: Suite =>

  override def afterAll(): Unit = {

    // stopping actor system
    log.info(s"Shutdowning actor system: $actorSystemName")
    TestKit.shutdownActorSystem(system)

    // stopping mongo instance
    super.afterAll()
  }

}
