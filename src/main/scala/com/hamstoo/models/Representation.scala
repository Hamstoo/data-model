package com.hamstoo.models

import com.hamstoo.utils.{StrWithBinaryPrefix, fieldName}
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.annotation.tailrec
import scala.util.Random

object Representation {
  type VecElem = Double
  type Vec = Seq[VecElem]

  implicit class DblWithPow(private val d: Double) extends AnyVal {
    def **(n: Double): Double = Math pow(d, n)
  }

  implicit class VecFunctions(private val vec: Vec) extends AnyVal {

    // vector arithmetic
    // an alternative to recursive functions would be to call `.zip` followed by `.map` which is less efficient due to
    // the construction of an intermediate collection between the steps. this can be helped by first calling `.view`
    // on the list to make evaluation lazy, though View construction also has its cost.
    def -(other: Vec): Vec = {
      @tailrec
      def rec(a: Vec, b: Vec, c: Vec): Vec = if (a.isEmpty) c.reverse else rec(a.tail, b.tail, (a.head - b.head) +: c)

      rec(vec, other, Nil)
    }

    def +(other: Vec): Vec = {
      @tailrec
      def rec(a: Vec, b: Vec, c: Vec): Vec = if (a.isEmpty) c.reverse else rec(a.tail, b.tail, (a.head + b.head) +: c)

      rec(vec, other, Nil)
    }

    // scalar arithmetic
    def /(divisor: VecElem): Vec = vec.map(_ / divisor)

    def *(multiplicand: VecElem): Vec = vec.map(_ * multiplicand)

    def mean: VecElem = vec.sum / vec.length

    def stdev: VecElem = {
      val mean = vec.mean
      Math sqrt (0.0 /: vec) (_ + _.-(mean) ** 2) / (vec.length - 1)
    }

    def dot(other: Vec): VecElem = {
      @tailrec
      def rec(a: Vec, b: Vec, sum: Double): Double = if (a.isEmpty) sum else rec(a.tail, b.tail, sum + a.head * b.head)

      rec(vec, other, 0.0)
    }

    def l2Norm: VecElem = Math sqrt (0.0 /: vec) (_ + _ ** 2)

    def cosine(other: Vec): VecElem = (vec dot other) / vec.l2Norm / other.l2Norm

    def l2Normalize: Vec = vec / vec.l2Norm
  }

  val ID: String = fieldName[Representation]("_id")
  val LNK: String = fieldName[Representation]("link")
  val LPREF: String = fieldName[Representation]("lprefx")
  val HEADR: String = fieldName[Representation]("header")
  val DTXT: String = fieldName[Representation]("doctext")
  val OTXT: String = fieldName[Representation]("othtext")
  val KWORDS: String = fieldName[Representation]("keywords")
  val VECR: String = fieldName[Representation]("vecrepr")
  val TSTAMP: String = fieldName[Representation]("timestamp")
  implicit val reprHandler: BSONDocumentHandler[Representation] = Macros.handler[Representation]

  /** Factory with id and timestamp generation. */
  def apply(lnk: String, hdr: String, dtxt: String, otxt: String, kwords: String, vec: Option[Vec]): Representation =
    Representation(
      Random.alphanumeric take 12 mkString,
      lnk,
      Array.emptyByteArray,
      hdr,
      dtxt,
      otxt,
      kwords,
      vec,
      DateTime.now.getMillis)
}

/**
  * This Representation class is used to store scraped and parsed textual
  * representations of URLs.  The scraping and parsing are performed by an
  * instance of a Cruncher.
  *
  * @param _id       Unique alphanumeric ID.
  * @param link      URL link used to generate this Representation.
  * @param header    Title and `h1` headers concatenated.
  * @param doctext   Document text.
  * @param othtext   Other text not included in document text.
  * @param keywords  Keywords from meta tags.
  * @param vecrepr   Array[Double] vector embedding of the texts.
  * @param timestamp Time of construction.
  */
case class Representation(
                           _id: String,
                           link: String,
                           var lprefx: Array[Byte],
                           header: String,
                           doctext: String,
                           othtext: String,
                           keywords: String,
                           vecrepr: Option[Representation.Vec],
                           timestamp: Long) {
  lprefx = link.prefx()
}
