package com.hamstoo.models

import com.hamstoo.utils.ExtendedString

import scala.language.higherKinds

/**
  * Trait that define method for sanitizing instance on XSS vulnerabilities,
  * and in future other type of vulnerabilities.
  */
trait Protector[A] {

  /** Sanitize all text based content */
  def protect(o: A): A
}

object Protectors {
  def seq[A](implicit pr: Protector[A]): Protector[Seq[A]] = (o: Seq[A]) => o.map(pr.protect)
  def set[A](implicit pr: Protector[A]): Protector[Set[A]] = (o: Set[A]) => o.map(pr.protect)

  implicit val strProtector: Protector[String] = (o: String) => o.sanitize
}
