package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.daos.MongoMarksDao
import com.hamstoo.models.Representation.VecEnum
import com.hamstoo.utils.{ExtendedString, INF_TIME, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import org.apache.commons.text.similarity.LevenshteinDistance
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * A ReprEngineProduct is something that is computed by the repr-engine.  Though perhaps all of our
  * object models should extend this trait.
  *
  * See also: https://stackoverflow.com/questions/15441589/scala-copy-case-class-with-generic-type
  */
trait ReprEngineProduct[T <: ReprEngineProduct[T]] {

  // "self-typing to T to force withTimeFrom to return this type" (see URL above)
  //self: T => def timeFrom: Long

  val id: String
  val timeFrom: Long
  val timeThru: Long

  def withTimeFrom(timeFrom: Long): T

  assert(nameOf[ReprEngineProduct[T]](_.id) == com.hamstoo.models.Mark.ID)
  assert(nameOf[ReprEngineProduct[T]](_.timeFrom) == com.hamstoo.models.Mark.TIMEFROM)
  assert(nameOf[ReprEngineProduct[T]](_.timeThru) == com.hamstoo.models.Mark.TIMETHRU)
}

/**
  * Returning an incomplete Representation from MongoRepresentatinoDao.search (i.e. a Representation that has several
  * of its fields set to None) by excluding fields from the MongoDB query is fragile because users of the returned
  * objects may easily forget that they are incomplete.  Fortunately this is exactly what type safety is for, so we
  * have this RSearchable base class that can be returned instead.
  *
  * This class cannot be a case class because `case class Representation` extends it, and a case class that extends
  * another class will not automagically implement a `copy` method if one already exists.
  */
class RSearchable(val id: String,
                  val header: Option[String],
                  val doctext: String,
                  val nWords: Option[Long],
                  val vectors: Map[String, Representation.Vec],
                  val score: Option[Double]) {
  /**
    * Return true if `oth`er repr is a likely duplicate of this one.  False positives possible.
    * TODO: need to measure this distribution to determine if `DUPLICATE_SIMILARITY_THRESHOLD` is sufficient
    */
  def isDuplicate(oth: RSearchable): Boolean = {

    // quickly test for identical doctexts first and otherwise use header as a filter on top of vec/edit similarities
    !doctext.isEmpty && doctext == oth.doctext || header == oth.header && (

      // The `editSimilarity` is really what we're after here, but it's really, really slow (6-20 seconds per
      // comparison) so we filter via `vecSimilarity` first.  The reason we don't just always use vecSimilarity is
      // because it has too many false positives, like, e.g., when a site has very few English words.
      vecSimilarity(oth) > Representation.DUPLICATE_VEC_SIMILARITY_THRESHOLD &&
      editSimilarity(oth) > Representation.DUPLICATE_EDIT_SIMILARITY_THRESHOLD
    )
  }

  /** Define `similarity` in one place so that it can be used in multiple. */
  def vecSimilarity(oth: RSearchable): Double = (for {
    thisVec <- vectors.get(VecEnum.IDF3.toString)
    othVec <- oth.vectors.get(VecEnum.IDF3.toString)
  } yield Representation.VecFunctions(thisVec).cosine(othVec)).getOrElse(0.0)

  /** Another kind of similarity, the opposite of (relative) edit distance. */
  def editSimilarity(oth: RSearchable): Double = {
    if (doctext.isEmpty && oth.doctext.isEmpty) 1.0
    else {
      val editDist = LevenshteinDistance.getDefaultInstance.apply(doctext, oth.doctext)
      val relDist = editDist / math.max(doctext.length, oth.doctext.length).toDouble // toDouble is important here
      1.0 - relDist
    }
  }
}

/**
  * Since RSearchable is a case class we need to implement these methods manually.  These methods are required by
  * BSONDocumentHandler[RSearchable].
  */
object RSearchable {

  def apply(id: String, header: Option[String], doctext: String, nWords: Option[Long],
            vectors: Map[String, Representation.Vec], score: Option[Double]): RSearchable =
    new RSearchable(id, header, doctext, nWords, vectors, score)

  def unapply(obj: RSearchable):
           Option[(String, Option[String], String, Option[Long], Map[String, Representation.Vec], Option[Double])] =
    Some(obj.id, obj.header, obj.doctext, obj.nWords, obj.vectors, obj.score)
}

/**
  * This Representation class is used to store scraped and parsed textual
  * representations of URLs.  The scraping and parsing are performed by an
  * instance of a RepresentationFactory.
  *
  * @param id         Unique alphanumeric ID.
  * @param link       URL link used to generate this Representation.
  * @param lprefx     Binary URL prefix for indexing by mongodb. Gets overwritten by class init.
  * @param header     Title and `h1` headers concatenated.
  * @param doctext    Document text.
  * @param othtext    Other text not included in document text.
  * @param keywords   Keywords from meta tags.
  * @param nWords     Approximate number of words from the 4 bins each normalized for their MongoDB Text Index weights.
  * @param autoGenKws Keywords generated from the 4 textual representations.  Based on BM25 and cosine similarities.
  * @param vectors    Map from vector computation methods to Array[Double] vector embeddings of the texts.
  * @param timeFrom   Time of construction/modification.
  * @param timeThru   Time of validity.  Long.MaxValue indicates current value.
  * @param versions   `data-model` project version and others, if provided.
  */
case class Representation(
                           override val id: String = generateDbId(Representation.ID_LENGTH),
                           link: Option[String],
                           var lprefx: Option[mutable.WrappedArray[Byte]] = None, // using hashable WrappedArray here
                           override val header: Option[String],
                           override val doctext: String,
                           othtext: Option[String],
                           keywords: Option[String],
                           override val nWords: Option[Long] = None,
                           override val vectors: Map[String, Representation.Vec],
                           autoGenKws: Option[Seq[String]],
                           timeFrom: TimeStamp = TIME_NOW,
                           timeThru: TimeStamp = INF_TIME,
                           var versions: Option[Map[String, String]] = None,
                           override val score: Option[Double] = None)
    extends RSearchable(id, header, doctext, nWords, vectors, score) with ReprEngineProduct[Representation] {

  lprefx = link.map(_.binaryPrefix)
  versions = Some(versions.getOrElse(Map.empty[String, String]) // conversion of null->string required only for tests
                    .updated("data-model", Option(getClass.getPackage.getImplementationVersion).getOrElse("null")))

  override def withTimeFrom(timeFrom: Long): Representation = this.copy(timeFrom = timeFrom)

  /** Fairly standard equals definition.  Required b/c of the overriding of hashCode. */
  override def equals(other: Any): Boolean = other match {
    case other: Representation => other.canEqual(this) && this.hashCode == other.hashCode
    case _ => false
  }

  /**
    * Avoid incorporating `score: Option[Double]` into the hash code. `Product` does not define its own `hashCode` so
    * `super.hashCode` comes from `Any` and so the implementation of `hashCode` that is automatically generated for
    * case classes has to be copy and pasted here.  More at the following link:
    * https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
    * And an explanation here: https://stackoverflow.com/a/44708937/2030627
    * TODO: why is this necessary?  it would be better to implement something like equalsIgnoreScore similar to
    * TODO: Mark.equalsIgnoreTimeStamps
    */
  override def hashCode: Int = this.score match {
    case None => scala.runtime.ScalaRunTime._hashCode(this)
    case Some(_) => this.copy(score = None).hashCode
  }
}

object Representation extends BSONHandlers {
  type Vec = Seq[Double]

  val DUPLICATE_VEC_SIMILARITY_THRESHOLD = 0.95
  val DUPLICATE_EDIT_SIMILARITY_THRESHOLD = 0.85

  implicit class VecFunctions(private val vec: Vec) extends AnyVal {

    // vector arithmetic
    // an alternative to recursive functions would be to call `.zip` followed by `.map` which is less efficient due to
    // the construction of an intermediate collection between the steps. this can be helped by first calling `.view`
    // on the list to make evaluation lazy, though View construction also has its cost.
    def -(other: Vec): Vec = {
      @tailrec
      def rec(a: Vec, b: Vec, c: Vec): Vec =
        if (a.isEmpty || b.isEmpty) c.reverse else rec(a.tail, b.tail, (a.head - b.head) +: c)
      rec(vec, other, Nil)
    }

    def +(other: Vec): Vec = {
      @tailrec
      def rec(a: Vec, b: Vec, c: Vec): Vec =
        if (a.isEmpty || b.isEmpty) c.reverse else rec(a.tail, b.tail, (a.head + b.head) +: c)
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
      def rec(a: Vec, b: Vec, sum: Double): Double =
        if (a.isEmpty || b.isEmpty) sum else rec(a.tail, b.tail, sum + a.head * b.head)
      rec(vec, other, 0.0)
    }

    // see `stdev` for a more explicit similar `foldLeft` notation
    def l2Norm: Double = math.sqrt((0.0 /: vec) (_ + math.pow(_, 2)))

    def cosine(other: Vec): Double = (vec dot other) / vec.l2Norm / other.l2Norm

    def covar(other: Vec): Double = (vec dot other) / vec.size - vec.sum / vec.size * other.sum / other.size

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
        CRPv2_max, /* most significant cluster per here: https://medium.com/kifi-engineering/from-word2vec-to-doc2vec-an
                      -approach-driven-by-chinese-restaurant-process-93d3602eaa31 */
        CRPv2_2nd, // second most significant cluster (i.e. don't combine 1st and 2nd at point of construction)
        PC1,       // first principal direction/axis (not a principal component b/c not a reconstructed repr of orig. X)
        PC2,       // second
        PC3,       // third
        PC4,       // fourth
        KM1,       // most significant k-means cluster by average bm25 score
        KM2,       // second most significant k-means cluster (i.e. don't combine 1st and 2nd at point of construction)
        KM3        // third
      = Value
  }

  /** Representation type enumeration. */
  object ReprType extends Enumeration {

    // URL page content fetched from repr-engine/ContentRetriever when user enters a URL mark from Add page
    // of the website.  If user attempts to mark a page that requires a login, repr-engine will not have access
    // to the user's login information and so whatever content is fetched will be necessarily "public".
    val PUBLIC,

    // URL page content fetched from browser extension when user clicks our bookmark star icon on browser
    // toolbar.  User could be privately logged into a site when performing this action, which is why we label
    // this content "private."
        PRIVATE,

    // Content obtained from user's mark including user's comments/highlights/notes/etc.
        USER_CONTENT = Value
  }

  // make it implicit
  implicit def reprType2String(reprType: ReprType.Value): String = reprType.toString

  /** Implicit class for converting an Either[ObjectId, ReprType.Value] into a Future[ObjectId]. */
  implicit class ExtendedEitherRepr(private val repr: Either[ObjectId, ReprType.Value]) /*extends AnyVal*/ {

    def toReprId(mark: Mark)(implicit marksDao: MongoMarksDao, ex: ExecutionContext): Future[ObjectId] = {
      repr.fold(
        rid => Future.successful(rid),
        rtyp => marksDao.retrieve(User(mark.userId), mark.id, timeFrom = Some(mark.timeFrom)).map { mbMark =>
          mbMark.flatMap(_.reprs.find(_.reprType == rtyp.toString)).map(_.reprId).getOrElse("")
        }
      )
    }
  }

  val ID_LENGTH: Int = 12

  val LNK: String = nameOf[Representation](_.link)
  val LPREFX: String = nameOf[Representation](_.lprefx)
  val HEADR: String = nameOf[Representation](_.header)
  val DTXT: String = nameOf[Representation](_.doctext)
  val OTXT: String = nameOf[Representation](_.othtext)
  val KWORDS: String = nameOf[Representation](_.keywords)
  val N_WORDS: String = nameOf[Representation](_.nWords)
  val VECS: String = nameOf[Representation](_.vectors)
  assert(nameOf[Representation](_.score) == com.hamstoo.models.Mark.SCORE)

  implicit val pageBsonHandler: BSONDocumentHandler[Page] = Macros.handler[Page]
  implicit val reprHandler: BSONDocumentHandler[Representation] = Macros.handler[Representation]
  implicit val rsearchBsonHandler: BSONDocumentHandler[RSearchable] = Macros.handler[RSearchable]
}