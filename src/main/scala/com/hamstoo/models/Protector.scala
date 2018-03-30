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

  implicit def traversableProtector[A, F[_] <: Traversable[A]](implicit pr: Protector[A]): Protector[F[A]] = (o: F[A]) => {
    o.map(pr.protect).asInstanceOf[F[A]]
  }

  implicit val strProtector: Protector[String] = (o: String) => o.sanitize
}
