package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.utils.ExtendedString
import com.github.rjeschke.txtmark
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

/**
  * Mark data model. The fields are:
  * - subj - the rated element as a string of text; either a header of the bookmarked page, or the rated string itself
  * - url - an optional url for bookmark
  * - rating - the value assigned to the ratee by the user, from 0.0 to 5.0
  * - tags - a set of tags assigned to the ratee by the user
  * - comment - an optional text comment (markdown syntax) authored by the user
  * - commentEncoded - markdown converted to HTML; set by class init
  *
  * An interesting side effect of the former implementation of `copy` (removed in commit
  * '681a1af' on 2017-06-12) was that it called `Mark.apply` which would set the `urlPrfx`
  * field.  A `copy` wouldn't typically be expected to perform such a side effect however,
  * so it has been removed and the `urlPrfx` must now be set explicitly with the
  * `ExtendedString.prefx` method.
  */
case class MarkData(
                     subj: String,
                     url: Option[String],
                     rating: Option[Double],
                     tags: Option[Set[String]],
                     comment: Option[String],
                     var commentEncoded: Option[String]) {

  commentEncoded = comment.map { c: String => // example: hello <a name="n" href="javascript:alert('xss')">*you*</a>

    // https://github.com/rjeschke/txtmark/issues/33
    val extended = "\n[$PROFILE$]: extended"

    // example: <p>hello &lt;a name=&ldquo;n&rdquo; href=&ldquo;javascript:alert('xss')&ldquo;><em>you</em></a></p>
    // notice the first < gets converted to &ldquo; but the </p> is unchanged
    val html = txtmark.Processor.process(c + extended)

    // example: <p>hello <a name=“n” href=“javascript:alert('xss')“><em>you</em></a></p>
    // convert that &ldquo; back to a < character
    val html2 = StringEscapeUtils.unescapeHtml4(html)

    // example: <p>hello <a rel="nofollow"><em>you</em></a></p>
    // https://jsoup.org/cookbook/cleaning-html/whitelist-sanitizer
    Jsoup.clean(html2, Whitelist.basic())
  }
}

object MarkData {
  /** This auxiliary factory is used for the purpose of importing bookmarks only. */
  def apply(subj: String, url: String, tags: Set[String]): MarkData = MarkData(subj, Some(url), None, Some(tags), None, None)
}

/**
  * User history (list) entry data model. An `Entry` is a `Mark` that belongs to a
  * particular user along with an ID and timestamp.
  *
  * The fields are:
  * - userId - owning user's UUID
  * - id - the mark's alphanumerical string, used as an identifier common with all the marks versions
  * - mark - user-provided content
  * - aux - additional fields holding satellite data
  *   - hlights - the array of IDs of all highlights made by user on the webpage and their evolutions
  *   - tabVisible - browser tab timing data
  *   - tabBground - browser tab timing data
  * - urlPrfx - binary prefix of `mark.url` for the purpose of indexing by mongodb; set by class init.
  *             Binary prefix is used as filtering and 1st stage of urls equality estimation
  *             https://en.wikipedia.org/wiki/Binary_prefix
  * - repId - id of a representation for this mark
  * - from - timestamp of last edit
  * - thru - the moment of time until which this version is latest
  *
  * `score` is not part of the documents in the database, but it is returned from
  * `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(
                 userId: UUID,
                 id: String,
                 mark: MarkData,
                 aux: MarkAux,
                 var urlPrfx: Option[mutable.WrappedArray[Byte]], // using hashable WrappedArray here
                 repId: Option[String],
                 timeFrom: Long,
                 timeThru: Long,
                 score: Option[Double] = None) {
  urlPrfx = mark.url map (_.prefx)

  /** Fairly standard equals definition.  Required b/c of the overriding of hashCode. */
  override def equals(other: Any): Boolean = other match {
    case other: Mark => other.canEqual(this) && this.hashCode == other.hashCode
    case _ => false
  }

  /** Avoid incorporating `score: Option[Double]` into the hash code. */
  override def hashCode: Int = this.score match {
    // `Product` does not define its own `hashCode` so `super.hashCode` comes from `Any` and so
    // the implementation of `hashCode` that is automatically generated for case classes has to
    // be copy and pasted here.  More at the following link:
    //   https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
    // And an explanation here: https://stackoverflow.com/a/44708937/2030627
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
  val REPR: String = nameOf[Mark](_.repId)
  val MILS: String = nameOf[Mark](_.timeFrom)
  val THRU: String = nameOf[Mark](_.timeThru)
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
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val auxBsonHandler: BSONDocumentHandler[MarkAux] = Macros.handler[MarkAux]
  implicit val markBsonHandler: BSONDocumentHandler[MarkData] = Macros.handler[MarkData]
  implicit val entryBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]
  implicit val markDataJsonFormat: OFormat[MarkData] = Json.format[MarkData]

  /** Factory with ID and timestamp generation. */
  def apply(userId: UUID, mark: MarkData, rep: Option[String]): Mark = Mark(
    userId,
    Random.alphanumeric take ID_LENGTH mkString,
    mark,
    MarkAux(None, None),
    None,
    rep,
    DateTime.now.getMillis,
    Long.MaxValue)
}
