package com.hamstoo.models

import com.hamstoo.utils.ExtendedString

import scala.language.higherKinds

/**
  * Main trait for of type-class group for defining protector behavior.
  *
  * TODO: In future will nice to have macro based protector generators
  * TODO: What about added macro generators, like `Protector.protect[A]`
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
trait Sanitizable[A] {

  /** Sanitize all text based content */
  def sanitize: A /*= {
    // TODO: 208: can we do this with reflection so that all string members get sanitized?
  }*/
}

/** Companion object that contains helper methods, and value*/
object Sanitizable {

  /** Helper method for creating traversable protectors */
  // TODO: 208: can we just use a single method with Traversable here instead?
  def seq[A <: Sanitizable[A]]: Traversable[A] => Traversable[A] = (o: Traversable[A]) => o.map(_.sanitize)
  //def set[A <: Sanitizable[A]](implicit pr: Protector[A]): Protector[Set[A]] = (o: Set[A]) => o.map(pr.protect)

  /** Base protector for all text based content */
  // TODO: 208: I (FWC) didn't even know this syntax was allowed.  Does the syntax have a name?
  //implicit val protector: Protector[String] = (unsafe: String) => unsafe.sanitize
}
