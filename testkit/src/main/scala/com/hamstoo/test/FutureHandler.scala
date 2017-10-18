package com.hamstoo.test

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

/**
  * Add necessary methods to handle future value
  */
trait FutureHandler extends ScalaFutures {

  implicit val pc: PatienceConfig = PatienceConfig(Span(100, Seconds), Span(1, Second))
}
