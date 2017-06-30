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
      None,
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
                           var lprefx: Option[Array[Byte]], // must be an Option so that it can be None for hashCode
                           header: String,                            // Array.emptyByteArray will *not* suffice
                           doctext: String,
                           othtext: String,
                           keywords: String,
                           vecrepr: Option[Representation.Vec],
                           timestamp: Long) {

  lprefx = if (link.isEmpty) None else Some(link.prefx)

  /** Fairly standard equals definition. */
  override def equals(other: Any): Boolean = other match {
    case other: Representation => other.canEqual(this) && this.hashCode == other.hashCode
    case _ => false
  }

  /** Avoid incorporating Java byte array (i.e. memory address) `lprefx` into the hash code. */
  override def hashCode: Int = this.link match {
    // note that when `hashCode` is overridden `super.hashCode` appears to have different behavior than
    // what is implemented here, see the test in RepresentationSpec regarding this, and more at the following
    // link: https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
    // And an explanation here: https://stackoverflow.com/a/44708937/2030627
    case x if x.isEmpty => scala.runtime.ScalaRunTime._hashCode(this) // NOT super.hashCode!
    case _ => 31 * (31 + this.copy(link = "").hashCode) + this.link.hashCode
  }
}
