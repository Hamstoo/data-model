package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.services.TikaInstance
import com.hamstoo.utils.{ExtendedString, INF_TIME, generateDbId}
import org.apache.commons.text.StringEscapeUtils
import org.commonmark.node._
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.joda.time.DateTime
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.Logger
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable


/**
  * User content data model. This case class is also used for front-end JSON formatting.
  * The fields are:
  *
  * @param subj           - the rated element as a string of text; either a header of the marked page, or the rated
  *                       string itself
  * @param url            - an optional url
  * @param rating         - the value assigned to the mark by the user, from 0.0 to 5.0
  * @param tags           - a set of tags assigned to the mark by the user
  * @param comment        - an optional text comment assigned to the mark by the user
  * @param commentEncoded - markdown converted to HTML; set by class init
  */
case class MarkData(
                     subj: String,
                     url: Option[String],
                     rating: Option[Double] = None,
                     tags: Option[Set[String]] = None,
                     comment: Option[String] = None,
                     var commentEncoded: Option[String] = None) {

  import MarkData._

  commentEncoded = comment.map { c: String => // example: <IMG SRC=JaVaScRiPt:alert('XSS')>

    // example: <p>&lt;IMG SRC=JaVaScRiPt:alert('XSS')&gt;</p>
    // https://github.com/atlassian/commonmark-java
    val document: Node = parser.parse(c)
    val html = renderer.render(document)

    // example: <p><IMG SRC=JaVaScRiPt:alert('XSS')></p>
    // convert that &ldquo; back to a < character
    val html2 = StringEscapeUtils.unescapeHtml4(html)

    // issue 121 needs to be implemented here, before `Jsoup.clean` to safeguard against XSS

    // example: <p><img></p>
    Jsoup.clean(html2, htmlTagsWhitelist)
  }

  /**
    * Merge two `MarkData`s with as little data loss as possible.  Not using `copy` here to ensure that if
    * additional fields are added to the constructor they aren't forgotten here.
    */
  def merge(oth: MarkData): MarkData = {

    if (subj != oth.subj)
      logger.warn(s"Merging two marks with different subjects '$subj' and '${oth.subj}'; ignoring latter")
    if (url.isDefined && oth.url.isDefined && url.get != oth.url.get)
      logger.warn(s"Merging two marks with different URLs ${url.get} and ${oth.url.get}; ignoring latter")

    MarkData(if (subj.length >= oth.subj.length) subj else oth.subj,
             url.orElse(oth.url),
             oth.rating.orElse(rating), // use new rating in case user's intent was to rate smth differently
             Some(tags.getOrElse(Nil.toSet) union oth.tags.getOrElse(Nil.toSet)),
             comment.map(_ + oth.comment.map(commentMergeSeparator + _).getOrElse("")).orElse(oth.comment)
    )
  }
}

object MarkData {
  val logger: Logger = Logger(classOf[MarkData])

  /* attention: mutable Java classes below.
  for markdown parsing/rendering: */
  lazy val parser: Parser = Parser.builder().build()
  lazy val renderer: HtmlRenderer = HtmlRenderer.builder().build()

  // for XSS filtering (https://jsoup.org/cookbook/cleaning-html/whitelist-sanitizer)
  lazy val htmlTagsWhitelist: Whitelist = Whitelist.relaxed()
    .addTags("hr") // horizontal rule
    .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer") /*
    https://medium.com/@jitbit/target-blank-the-most-underestimated-vulnerability-ever-96e328301f4c : */
    .addEnforcedAttribute("a", "target", "_blank")

  val commentMergeSeparator: String = "\n\n---\n\n"
}

/**
  * This is the data structure used to store external content, e.g. HTML files or PDFs.  It could be private content
  * downloaded via the chrome extension, the repr-engine downloads public content given a URL, or the file upload
  * process uploads content directly from the user's computer.
  */
case class Page(mimeType: String, content: mutable.WrappedArray[Byte])

object Page {

  /** A separate `apply` method that detects the MIME type automagically with Tika. */
  def apply(content: mutable.WrappedArray[Byte]): Page = {
    val mimeType = TikaInstance.detect(content.toArray[Byte])
    Page(mimeType, content)
  }
}

/**
  * User history (list) entry data model. An `Entry` is a `Mark` that belongs to a
  * particular user along with an ID and timestamp.
  *
  * The fields are:
  *
  * @param userId   - owning user's UUID
  * @param id       - the mark's alphanumerical string, used as an identifier common with all the marks versions
  * @param mark     - user-provided content
  * @param aux      - additional fields holding satellite data
  * @param urlPrfx  - binary prefix of `mark.url` for the purpose of indexing by mongodb; set by class init
  *                 Binary prefix is used as filtering and 1st stage of urls equality estimation
  *                 https://en.wikipedia.org/wiki/Binary_prefix
  * @param page     - temporary holder for page sources, until a representation is constructed or assigned
  * @param pubRepr  - optional public page representation id for this mark
  * @param privRepr - optional personal user content representation id for this mark
  * @param timeFrom - timestamp of last edit
  * @param timeThru - the moment of time until which this version is latest
  * @param mergeId  - if this mark was merged into another, this will be the ID of that other
  *
  * @param score    - `score` is not part of the documents in the database, but it is returned from
  *                 `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(
                 userId: UUID,
                 id: String = generateDbId(Mark.ID_LENGTH),
                 mark: MarkData,
                 aux: Option[MarkAux] = Some(MarkAux(None, None)),
                 var urlPrfx: Option[mutable.WrappedArray[Byte]] = None, // using hashable WrappedArray here
                 page: Option[Page] = None,
                 pubRepr: Option[String] = None,
                 privRepr: Option[String] = None,
                 timeFrom: Long = DateTime.now.getMillis,
                 timeThru: Long = INF_TIME,
                 mergeId: Option[String] = None,
                 score: Option[Double] = None) {
  urlPrfx = mark.url map (_.binaryPrefix)

  import Mark._

  /** Use the private repr when available, o/w use the public one. */
  def primaryRepr: String = privRepr.orElse(pubRepr).getOrElse("")

  /** Return true if the mark is representable but not yet represented. */
  def representablePublic: Boolean = pubRepr.isEmpty && mark.url.isDefined
  def representablePrivate: Boolean = privRepr.isEmpty && page.isDefined

  /** Return true if the mark is current (i.e. hasn't been updated or deleted). */
  def isCurrent: Boolean = timeThru == INF_TIME

  /**
    * Merge two marks.  This method is called from the `repr-engine` when a new mark's, `oth`, representations are
    * similar enough (according to `Representation.isDuplicate`) to an existing mark's, `this`.  So `oth` probably
    * won't have it's reprs set--unless one of them was set and the other not.
    */
  def merge(oth: Mark): Mark = {
    assert(this.userId == oth.userId)

    // warning messages
    if (pubRepr.isDefined && oth.pubRepr.isDefined && pubRepr.get != oth.pubRepr.get)
      logger.warn(s"Merging two marks, $id and ${oth.id}, with different public representations ${pubRepr.get} and ${oth.pubRepr.get}; ignoring latter")
    if (privRepr.isDefined && oth.privRepr.isDefined && privRepr.get != oth.privRepr.get)
      logger.warn(s"Merging two marks, $id and ${oth.id}, with different private representations ${privRepr.get} and ${oth.privRepr.get}; ignoring latter")

    // TODO: how do we ensure that additional fields added to the constructor are accounted for here?
    // TODO: how do we ensure that other data (like highlights) that reference markIds are accounted for?
    copy(mark = mark.merge(oth.mark),

         aux = if (oth.aux.isDefined) aux.map(_.merge(oth.aux.get)).orElse(oth.aux) else aux,

         // `page`s should all have been processed already if any private repr is defined, so only merge them if
         // that is not the case; in which case it's very unlikely that both will be defined, but use newer one if so
         // just in case the user wasn't logged in originally or something (but then `Representation.isDuplicate`
         // would have returned false, so we wouldn't even be here in this awkward position)
         page = if (Seq(privRepr, oth.privRepr).exists(_.isDefined)) None else oth.page.orElse(page),

         // it's remotely possible that these are different, which we warn about above
         pubRepr  = pubRepr .orElse(oth.pubRepr ),
         privRepr = privRepr.orElse(oth.privRepr)
    )
  }

  /** Fairly standard equals definition.  Required b/c of the overriding of hashCode. */
  override def equals(other: Any): Boolean = other match {
    case other: Mark => other.canEqual(this) && this.hashCode == other.hashCode
    case _ => false
  }

  /**
    * Avoid incorporating `score: Option[Double]` into the hash code. `Product` does not define its own `hashCode` so
    * `super.hashCode` comes from `Any` and so the implementation of `hashCode` that is automatically generated for
    * case classes has to be copy and pasted here.  More at the following link:
    * https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
    * And an explanation here: https://stackoverflow.com/a/44708937/2030627
    */
  override def hashCode: Int = this.score match {
    case None => scala.runtime.ScalaRunTime._hashCode(this)
    case Some(_) => this.copy(score = None).hashCode
  }
}

object Mark extends BSONHandlers {
  val logger: Logger = Logger(classOf[Mark])

  // probably a good idea to log this somewhere, and this seems like a good place for it to only happen once
  logger.info("data-model version " + Option(getClass.getPackage.getImplementationVersion).getOrElse("null"))

  case class RangeMils(begin: Long, end: Long)

  /** Auxiliary stats pertaining to a `Mark`. */
  case class MarkAux(tabVisible: Option[Seq[RangeMils]], tabBground: Option[Seq[RangeMils]]) {

    /** Not using `copy` in this merge to ensure if new fields are added, they aren't forgotten here. */
    def merge(oth: MarkAux) =
      MarkAux(Some(tabVisible.getOrElse(Nil) ++ oth.tabVisible.getOrElse(Nil)),
              Some(tabBground.getOrElse(Nil) ++ oth.tabBground.getOrElse(Nil)))
  }

  val ID_LENGTH: Int = 16

  val USR: String = nameOf[Mark](_.userId)
  val ID: String = nameOf[Mark](_.id)
  val MARK: String = nameOf[Mark](_.mark)
  val AUX: String = nameOf[Mark](_.aux)
  val URLPRFX: String = nameOf[Mark](_.urlPrfx)
  val PAGE: String = nameOf[Mark](_.page)
  val PUBREPR: String = nameOf[Mark](_.pubRepr)
  val PRVREPR: String = nameOf[Mark](_.privRepr)
  val TIMEFROM: String = nameOf[Mark](_.timeFrom)
  val TIMETHRU: String = nameOf[Mark](_.timeThru)
  val MERGEID: String = nameOf[Mark](_.mergeId)
  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = nameOf[Mark](_.score)
  val SUBJ: String = nameOf[MarkData](_.subj)
  val URL: String = nameOf[MarkData](_.url)
  val STARS: String = nameOf[MarkData](_.rating)
  val TAGS: String = nameOf[MarkData](_.tags)
  val COMNT: String = nameOf[MarkData](_.comment)
  val COMNTENC: String = nameOf[MarkData](_.commentEncoded)
  val TABVIS: String = nameOf[MarkAux](_.tabVisible)
  val TABBG: String = nameOf[MarkAux](_.tabBground)
  implicit val pageBsonHandler: BSONDocumentHandler[Page] = Macros.handler[Page]
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val auxBsonHandler: BSONDocumentHandler[MarkAux] = Macros.handler[MarkAux]
  implicit val markBsonHandler: BSONDocumentHandler[MarkData] = Macros.handler[MarkData]
  implicit val entryBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]
  implicit val markDataJsonFormat: OFormat[MarkData] = Json.format[MarkData]
}
