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
    def -(subtrahend: Double): Vec = vec.map(_ - subtrahend)
    def /(divisor: Double): Vec = vec.map(_ / divisor)
    def *(multiplicand: Double): Vec = vec.map(_ * multiplicand)

    def mean: Double = vec.sum / vec.length

    def variance: Double = {
      val mean = vec.mean
      // see `l2Norm` for a more implicit similar `foldLeft` notation
      val fold = vec.foldLeft(0.0) { case (s, x) => s + math.pow(x - mean, 2) }
      fold / (vec.size - 1)
    }

    def stdev: Double = math.sqrt(variance) // this was formerly wrong: math.sqrt(fold) / (vec.length - 1)

    def centralMoment(moment: Double): Double = {
      val mean = vec.mean
      vec.foldLeft(0.0) { case (s, x) => s + math.pow(x - mean, moment) } / vec.size
    }

    def skew: Double = vec.centralMoment(3) / math.pow(vec.stdev, 3)
    def kurt: Double = vec.centralMoment(4) / math.pow(vec.stdev, 4)

    def dot(other: Vec): Double = {
      @tailrec
      def rec(a: Vec, b: Vec, sum: Double): Double = if (a.isEmpty) sum else rec(a.tail, b.tail, sum + a.head * b.head)
      rec(vec, other, 0.0)
    }

    // see `stdev` for a more explicit similar `foldLeft` notation
    def l2Norm: Double = math.sqrt( (0.0 /: vec) (_ + math.pow(_, 2)) )

    def cosine(other: Vec): Double = (vec dot other) / vec.l2Norm / other.l2Norm

    def covar(other: Vec): Double = (vec dot other)/vec.size - vec.sum/vec.size * other.sum/other.size

    def corr(other: Vec): Double = {
      // https://en.wikipedia.org/wiki/Correlation_and_dependence
      val correctedCovar = (vec covar other) * vec.size / (vec.size - 1)
      correctedCovar / vec.stdev / other.stdev
    }

    def beta(x: Vec): Double = (vec covar x) / x.variance

    def l2Normalize: Vec = vec / vec.l2Norm
  }

  /**
    * Enumeration of various types of vectors that can end up in the `Representation.vectors` map.
    * On scala.Enumeration: http://underscore.io/blog/posts/2014/09/03/enumerations.html
    */
  object VecEnum extends Enumeration {
    //type VecEnum = Value
    val IDF,       // document vectors constructed by IDF weighted average of word vectors
        IDF3,      // IDF^3 weighted (e.g. IDFs of 5 and 10, 2x difference, converted to 8x difference)
        CRPv2_max, // most significant cluster per here: https://medium.com/kifi-engineering/from-word2vec-to-doc2vec-an-approach-driven-by-chinese-restaurant-process-93d3602eaa31
        CRPv2_2nd, // second most significant cluster (i.e. don't combine 1st and 2nd at point of construction)
        PC1,       // first principal direction/axis (not a principal component b/c not a reconstructed repr of orig. X)
        PC2,       // second
        PC3        // third
      = Value
  }
  //implicit val vecEnumHandler: BSONDocumentHandler[VecEnum.Value] = Macros.handler[VecEnum.Value]

  val ID: String = nameOf[Representation](_.id)
  val LNK: String = nameOf[Representation](_.link)
  val LPREF: String = nameOf[Representation](_.lprefx)
  val HEADR: String = nameOf[Representation](_.header)
  val DTXT: String = nameOf[Representation](_.doctext)
  val OTXT: String = nameOf[Representation](_.othtext)
  val KWORDS: String = nameOf[Representation](_.keywords)
  val VECS: String = nameOf[Representation](_.vectors)
  val TIMEFROM: String = nameOf[Representation](_.timeFrom)
  val TIMETHRU: String = nameOf[Representation](_.timeThru)
  implicit val reprHandler: BSONDocumentHandler[Representation] = Macros.handler[Representation]

  /** Factory with id and timestamp generation. */
  def apply(
             lnk: Option[String],
             hdr: String,
             dtxt: String,
             otxt: String,
             kwords: String,
             vec: Map[String, Vec]): Representation =
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
  * instance of a RepresentationFactory.
  *
  * @param id       Unique alphanumeric ID.
  * @param link     URL link used to generate this Representation.
  * @param lprefx   Binary URL prefix for indexing by mongodb. Gets overwritten by class init.
  * @param header   Title and `h1` headers concatenated.
  * @param doctext  Document text.
  * @param othtext  Other text not included in document text.
  * @param keywords Keywords from meta tags.
  * @param vectors  Map from vector computation methods to Array[Double] vector embeddings of the texts.
  * @param timeFrom Time of construction/modification.
  * @param timeThru Time of validity.
  */
case class Representation(
                           id: String,
                           link: Option[String],
                           var lprefx: Option[mutable.WrappedArray[Byte]], // using hashable WrappedArray here
                           header: String,
                           doctext: String,
                           othtext: String,
                           keywords: String,
                           vectors: Map[String, Representation.Vec],
                           timeFrom: Long,
                           timeThru: Long) {
  lprefx = link.map(_.prefx)
}
