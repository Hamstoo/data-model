package com.hamstoo.models

/**
  * Trait that define method for sanitizing instance on XSS vulnerabilities,
  * and in future other type of vulnerabilities.
  */
trait Protected[A] {

  /** Sanitize all text based content */
  def protect: A
}
