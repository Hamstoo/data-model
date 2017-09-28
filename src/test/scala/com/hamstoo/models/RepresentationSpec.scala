package com.hamstoo.models

import com.hamstoo.models.Representation._
import com.hamstoo.utils.{FlatSpecWithMatchers, MediaType}

import scala.util.Random

/**
  * Representation model tests.
  */
class RepresentationSpec extends FlatSpecWithMatchers {

  // two vectors and a scalar walk into a bar....
  val v0: Vec = Seq(1, 2, 3)
  val v1: Vec = Seq(4, 5, 6)
  val s: Double = 2.0

  "Representation" should "* be consistently hashable" in {
    def rep = Representation(
      id = "",
      link = Some("xyz"),
      page = Page(MediaType.TEXT_HTML.toString, "sdf".getBytes),
      header = "",
      doctext = "",
      othtext = "",
      keywords = "",
      vectors = Map.empty[String, Vec],
      autoGenKws = Some(Seq("keyword0", "keyword1")),
      timeFrom = 0)

    val (a, b) = (rep, rep)
    a.hashCode shouldEqual b.hashCode
    a shouldEqual b
  }

    "Vectors" should "* be additive" in {
      val r: Vec = v0 + v1
      (r, v0, v1).zipped[Double, Vec, Double, Vec, Double, Vec].foreach {
        case (ri, v0i, v1i) => ri shouldEqual v0i + v1i
      }
    }

    it should "* be subtractive" in  {
      val r: Vec = v0 - v1
      (r, v0, v1).zipped[Double, Vec, Double, Vec, Double, Vec].foreach {
        case (ri, v0i, v1i) => ri shouldEqual v0i - v1i
      }
    }

    it should "* be divisable by scalars" in {
      val r: Vec = v0 / s
      (r, v0).zipped[Double, Vec, Double, Vec].foreach { case (ri, v0i) => ri shouldEqual v0i / s }
    }

    it should "* be multiplicable by scalars" in {
      val r: Vec = v0 * s
      (r, v0).zipped[Double, Vec, Double, Vec].foreach { case (ri, v0i) => ri shouldEqual v0i * s }
    }

    it should "* be average-able" in {
      v0.mean shouldEqual v0(1)
    }

    it should "* be stdev-able" in {
      v0.stdev shouldEqual math.sqrt((math.pow(1 - 2, 2) + math.pow(3 - 2, 2)) / (3 - 1))
    }

    it should "* be skew-able" in {
      val randGen = new Random(0)
      val v: Vec = (0 until 1000).map(_ => randGen.nextGaussian)
      v.skew shouldEqual  -0.0361 +- 1e-3 // normal distribution should be 0.0
      v0.skew shouldEqual 0.0
    }

    it should "* be kurt-able" in {
      val randGen = new Random(0)
      val v: Vec = (0 until 1000).map(_ => randGen.nextGaussian)
      v.kurt shouldEqual 2.95 +- 2.96 // normal distribution should be 3.0
      v0.kurt shouldEqual 0.666667 +- 1e-5
    }

    it should "* be covar-able" in {
      v0.covar(v1) shouldEqual ((1 - 2) * (4 - 5) + (3 - 2) * (6 - 5)).toDouble / 3 +- 1e-15
    }

    it should "* be dot-product-able" in {
      val r: Double = v0 dot v1
      r shouldEqual (v0.head * v1.head + v0(1) * v1(1) + v0(2) * v1(2))
    }

    it should "* be L2-norm-able" in {
      v0.l2Norm shouldEqual math.sqrt(math.pow(v0.head, 2) + math.pow(v0(1), 2) + math.pow(v0(2), 2))
    }

    it should "* be cosine-similarity-able" in {
      val r: Double = v0 cosine v1
      r shouldEqual 0.9746 +- 1e-4
    }

    it should "* be PEMDAS-able (i.e. support proper mathematical order of operations)" in {
      var r: Vec = v0 + v1 / s // division must happen first
      (r, v0, v1).zipped[Double, Vec, Double, Vec, Double, Vec].foreach {
        case (ri, v0i, v1i) => ri shouldEqual v0i + (v1i / s)
      }
      r = v0 / s * s // scalar multiplication must happen second
      (r, v0).zipped[Double, Vec, Double, Vec].foreach { case (ri, v0i) => ri shouldEqual (v0i / s) * s }
    }

  // this is the reason that `DblWithPow` is commented out in Representation.scala
  /*"DblWithPow" should {
    "* be PEMDAS-able (but it's not!)" in {
      val r: Double = 2.0 * 3.0**4.0
      r mustEqual 2.0 * math.pow(3.0, 4.0) // 1296.0 != 162.0
    }
  }*/
}
