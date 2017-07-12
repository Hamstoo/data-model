package com.hamstoo.models

import com.github.dwickern.macros.NameOf._
import com.hamstoo.utils.ExtendedString
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Random

object Representation extends BSONHandlers {
  type Vec = Seq[Double]

  // FWC - I'm afraid that someone with fairly typical assumptions about typical mathematical order of
  // operations (PEMDAS) would improperly and/or inadvertently use this method leading to a fairly tricky
  // bug to track down (see commented out test in RepresentationSpec.scala).
  /*implicit class DblWithPow(private val d: Double) extends AnyVal {
    def **(n: Double): Double = math pow(d, n)
  }*/

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
    def /(divisor: Double): Vec = vec.map(_ / divisor)

    def *(multiplicand: Double): Vec = vec.map(_ * multiplicand)

    def mean: Double = vec.sum / vec.length

    def stdev: Double = {
      val mean = vec.mean // see `l2Norm` for a more implicit similar `foldLeft` implementation
      math.sqrt( vec.foldLeft(0.0) { case (s, x) => s + math.pow(x - mean, 2) } ) / (vec.length - 1)
    }

    def dot(other: Vec): Double = {
      @tailrec
      def rec(a: Vec, b: Vec, sum: Double): Double = if (a.isEmpty) sum else rec(a.tail, b.tail, sum + a.head * b.head)
      rec(vec, other, 0.0)
    }

    // see `stdev` for a more explicit similar `foldLeft` implementation
    def l2Norm: Double = Math sqrt (0.0 /: vec) (_ + math.pow(_, 2))

    def cosine(other: Vec): Double = (vec dot other) / vec.l2Norm / other.l2Norm

    def l2Normalize: Vec = vec / vec.l2Norm
  }

  val ID: String = nameOf[Representation](_.id)
  val LNK: String = nameOf[Representation](_.link)
  val LPREF: String = nameOf[Representation](_.lprefx)
  val HEADR: String = nameOf[Representation](_.header)
  val DTXT: String = nameOf[Representation](_.doctext)
  val OTXT: String = nameOf[Representation](_.othtext)
  val KWORDS: String = nameOf[Representation](_.keywords)
  val VECR: String = nameOf[Representation](_.vecrepr)
  val TSTAMP: String = nameOf[Representation](_.from)
  val CURRNT: String = nameOf[Representation](_.thru)
  implicit val reprHandler: BSONDocumentHandler[Representation] = Macros.handler[Representation]

  /** Factory with id and timestamp generation. */
  def apply(
             lnk: Option[String],
             hdr: String,
             dtxt: String,
             otxt: String,
             kwords: String,
             vec: Option[Vec]): Representation =
    Representation(
      Random.alphanumeric take 12 mkString,
      lnk,
      None,
      hdr,
      dtxt,
      otxt,
      kwords,
      vec,
      DateTime.now.getMillis,
      Long.MaxValue)
}

/**
  * This Representation class is used to store scraped and parsed textual
  * representations of URLs.  The scraping and parsing are performed by an
  * instance of a Cruncher.
  *
  * @param id       Unique alphanumeric ID.
  * @param link     URL link used to generate this Representation.
  * @param header   Title and `h1` headers concatenated.
  * @param doctext  Document text.
  * @param othtext  Other text not included in document text.
  * @param keywords Keywords from meta tags.
  * @param vecrepr  Array[Double] vector embedding of the texts.
  * @param from     Time of construction.
  */
case class Representation(
                           id: String,
                           link: Option[String],
                           var lprefx: Option[mutable.WrappedArray[Byte]], // using hashable WrappedArray here
                           header: String,
                           doctext: String,
                           othtext: String,
                           keywords: String,
                           vecrepr: Option[Representation.Vec],
                           from: Long,
                           thru: Long) {
  lprefx = link.map(_.prefx)
}
