package com.hamstoo.utils

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}

trait FutureHandler extends ScalaFutures {

  implicit val pc: PatienceConfig = PatienceConfig(Span(20, Seconds), Span(1, Second))
}
