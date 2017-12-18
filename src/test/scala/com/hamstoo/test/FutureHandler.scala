package com.hamstoo.test

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

/**
  * Trait that provide method to working with future value
  */
trait FutureHandler extends ScalaFutures {

  // required to handle future
  implicit val pc: PatienceConfig = PatienceConfig(Span(214, Seconds), Span(1, Second))
}
