package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.utils.ExtendedString
import org.apache.commons.text.StringEscapeUtils
import org.commonmark.node._
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.joda.time.DateTime
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

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

  commentEncoded = comment.map { c: String => // example: <IMG SRC=JaVaScRiPt:alert('XSS')>

    /* example: <p>&lt;IMG SRC=JaVaScRiPt:alert('XSS')&gt;</p>
    // https://github.com/atlassian/commonmark-java */
    val document: Node = MarkData.parser.parse(c)
    val html = MarkData.renderer.render(document)

    /* example: <p><IMG SRC=JaVaScRiPt:alert('XSS')></p>
    // convert that &ldquo; back to a < character */
    val html2 = StringEscapeUtils.unescapeHtml4(html)

    // example: <p><img></p>
    Jsoup.clean(html2, MarkData.htmlTagsWhitelist)
  }
}

object MarkData {
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
}

case class Page(mimeType: String, content: mutable.WrappedArray[Byte])

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
  *
  * @param score    - `score` is not part of the documents in the database, but it is returned from
  *                 `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(
                 userId: UUID,
                 id: String = Random.alphanumeric take Mark.ID_LENGTH mkString,
                 mark: MarkData,
                 aux: MarkAux = MarkAux(None, None),
                 var urlPrfx: Option[mutable.WrappedArray[Byte]] = None, // using hashable WrappedArray here
                 page: Option[Page] = None,
                 pubRepr: Option[String] = None,
                 privRepr: Option[String] = None,
                 timeFrom: Long = DateTime.now.getMillis,
                 timeThru: Long = Long.MaxValue,
                 score: Option[Double] = None) {
  urlPrfx = mark.url map (_.prefx)

  /** Use the private repr when available, o/w use the public one. */
  def primaryRepr: String = privRepr.orElse(pubRepr).getOrElse("")

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

  case class RangeMils(begin: Long, end: Long)

  case class MarkAux(tabVisible: Option[Seq[RangeMils]], tabBground: Option[Seq[RangeMils]])

  val ID_LENGTH: Int = 16
  val USER: String = nameOf[Mark](_.userId)
  val ID: String = nameOf[Mark](_.id)
  val MARK: String = nameOf[Mark](_.mark)
  val AUX: String = nameOf[Mark](_.aux)
  val UPRFX: String = nameOf[Mark](_.urlPrfx)
  val PAGE: String = nameOf[Mark](_.page)
  val PUBREPR: String = nameOf[Mark](_.pubRepr)
  val PRVREPR: String = nameOf[Mark](_.privRepr)
  val TIMEFROM: String = nameOf[Mark](_.timeFrom)
  val TIMETHRU: String = nameOf[Mark](_.timeThru)
  // `text` index search score <projectedFieldName>, not a field name of the collection:
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
