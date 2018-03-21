package com.hamstoo.models

/**
  * Trait that define one method for checking instance on XSS vulnerabilities ,
  * and in future other type of vulnerabilities.
  */
trait SafeContent {

  /** method for checking if object contain dangerous content */
  def isSafe: Boolean
}
