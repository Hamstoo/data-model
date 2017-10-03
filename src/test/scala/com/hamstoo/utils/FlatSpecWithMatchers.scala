package com.hamstoo.utils

import org.scalatest.{FlatSpecLike, Matchers}

/**
  * Trait that combine FlatSpecLike trait with Matchers for simply test suites
  */
trait FlatSpecWithMatchers extends FlatSpecLike with Matchers
