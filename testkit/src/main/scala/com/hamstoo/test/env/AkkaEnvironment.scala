package com.hamstoo.test.env

import akka.actor.ActorSystem
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

  override def afterAll(): Unit = {

    //stopping actor system
    TestKit.shutdownActorSystem(system)
  }

}
