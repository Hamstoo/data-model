package services

import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import org.specs2.mutable.Specification
import org.specs2.specification.Scope


/**
  * Representation model tests.
  */
class RepresentationSpec extends Specification {

  "Representation" should {
    "* be consistently hashable" in {
      def rep = Representation("", Some("xyz"), None, "", "", "", "", None, 0, Long.MaxValue)
      val (a, b) = (rep, rep)
      a.hashCode mustEqual b.hashCode
      a mustEqual b
    }
  }

  trait system extends Scope {
    // two vectors and a scalar walk into a bar....
    val v0: Vec = Seq(1, 2, 3)
    val v1: Vec = Seq(4, 5, 6)
    val s: Double = 2.0
  }

  "Vectors" should {
    "* be additive" in new system {
      val r: Vec = v0 + v1
      (r, v0, v1).zipped.foreach { case (ri, v0i, v1i) => ri mustEqual v0i + v1i }
    }

    "* be subtractive" in new system {
      val r: Vec = v0 - v1
      (r, v0, v1).zipped.foreach { case (ri, v0i, v1i) => ri mustEqual v0i - v1i }
    }

    "* be divisable by scalars" in new system {
      val r: Vec = v0 / s
      (r, v0).zipped.foreach { case (ri, v0i) => ri mustEqual v0i / s }
    }

    "* be multiplicable by scalars" in new system {
      val r: Vec = v0 * s
      (r, v0).zipped.foreach { case (ri, v0i) => ri mustEqual v0i * s }
    }

    "* be average-able" in new system {
      v0.mean mustEqual v0(1)
    }

    "* be stdev-able" in new system {
      v0.stdev mustEqual math.sqrt(2) / 2
    }

    "* be dot-product-able" in new system {
      val r: Double = v0 dot v1
      r mustEqual (v0.head * v1.head + v0(1) * v1(1) + v0(2) * v1(2) )
    }

    "* be L2-norm-able" in new system {
      v0.l2Norm mustEqual math.sqrt(math.pow(v0.head, 2) + math.pow(v0(1), 2) + math.pow(v0(2), 2))
    }

    "* be cosine-similarity-able" in new system {
      val r: Double = v0 cosine v1
      r must beCloseTo(0.9746, 1e-4)
    }

    "* be PEMDAS-able (i.e. support proper mathematical order of operations)" in new system {
      var r: Vec = v0 + v1 / s // division must happen first
      (r, v0, v1).zipped.foreach { case (ri, v0i, v1i) => ri mustEqual v0i + (v1i / s) }
      r = v0 / s * s // scalar multiplication must happen second
      (r, v0).zipped.foreach { case (ri, v0i) => ri mustEqual (v0i / s) * s }
    }
  }

  // this is the reason that `DblWithPow` is commented out in Representation.scala
  /*"DblWithPow" should {
    "* be PEMDAS-able (but it's not!)" in {
      val r: Double = 2.0 * 3.0**4.0
      r mustEqual 2.0 * math.pow(3.0, 4.0) // 1296.0 != 162.0
    }
  }*/
}
