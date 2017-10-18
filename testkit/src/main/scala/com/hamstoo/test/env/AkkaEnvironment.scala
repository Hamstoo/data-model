package com.hamstoo.test.env

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.hamstoo.test.FlatSpecWithMatchers
import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, Suite}

abstract class AkkaEnvironment(actorSystemName: String)
  extends TestKit(ActorSystem(actorSystemName))
    with FlatSpecWithMatchers
    with BeforeAndAfterAll {

  self: Suite =>

  val log = Logger(classOf[AkkaEnvironment])

  override def afterAll(): Unit = {

    //stopping actor system
    log.info(s"Shutdowning actor system $actorSystemName")
    TestKit.shutdownActorSystem(system)
  }

}
