package com.hamstoo.models

import com.hamstoo.utils.ExtendedString

import scala.language.higherKinds

// TODO: What about added macro generators, like `Protector.protect[A]`
/**
  * Main trait for of type-class group for defining protector behavior.
  * In future will nice to have macro based protector generators
  *
  * Example:
  *
  *       Mark all field that will be sanitized by annotation marker @sanitize
  *
  * case class TestEntity(@sanitize name: String, age: Int, @sanitize brothers: Seq[String])
  * implicit val pr: Protector[TestEntity] = ProtectorMacro.protector[TestEntity]
  *
  *       Will generate at compile time:
  *
  * implicit val pr: Protector[TestEntity] = {
  *   import Protectors.strProtector
  *   der testEntityProtector()(implicit seqStrPr: Protector[Seq[String]], strPr: Protector[String]): Protector[TestEntity] = (o: TestEntity) => {
  *     o.copy(name = strPr.protect(o.name), brothers = seqStrPr.protect(o.brothers)
  *   }
  *
  *   testEntityProtector()
  * }
  */
trait Protector[A] {

  /** Sanitize all text based content */
  def protect(o: A): A
}

/** Companion object that contains helper methods, and value*/
object Protectors {

  /** Helper method for creating traversable protectors */
  def seq[A](implicit pr: Protector[A]): Protector[Seq[A]] = (o: Seq[A]) => o.map(pr.protect)
  def set[A](implicit pr: Protector[A]): Protector[Set[A]] = (o: Set[A]) => o.map(pr.protect)

  /** Base protector for all text based content */
  implicit val strProtector: Protector[String] = (o: String) => o.sanitize
}
