package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.utils.ExtendedString
import org.joda.time.DateTime
import play.api.libs.json.{JsValue, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

case class RangeMils(begin: Long, end: Long)

case class HLPos(path: String, text: String, indx: Int)

/**
  * Data model of a text highlight. The fields are:
  * - id - highlight id;
  * - pos - array of positioning data with these fields:
  *   - path - the CSS XPath of HTML element used to locate the highlight;
  *   - text - the highlighted piece of text;
  *   - indx - the number of the first character in the selection relative to the whole string in HTML element;
  * - from - timestamp;
  * - thru - version validity time.
  */
case class Highlight(id: String, pos: Seq[HLPos], from: Long, thru: Long)

/**
  * Ratee data model. The fields are:
  * - subj - the rated element as a string of text; either a header of the bookmarked
  * page, or the rated string itself;
  * - url - an optional url for bookmark;
  * - urlPrfx - first characters of the url for indexing purpose;
  * - repId - a String identifier of the representation equivalent of the ratee;
  * - rating - the value assigned to the ratee by the user, from 0.0 to 5.0;
  * - tags - a set of tags assigned to the ratee by the user;
  * - comment - an optional text comment assigned to the ratee by the user;
  * - highlights - a set of highlighted pieces of text from the webpage.
  *
  * An interesting side effect of the former implementation of `copy` (removed in commit
  * '681a1af' on 2017-06-12) was that it called `Mark.apply` which would set the `urlPrfx`
  * field.  A `copy` wouldn't typically be expected to perform such a side effect however,
  * so it has been removed and the `urlPrfx` must now be set explicitly with the
  * `computeUrlPrefix` method.
  */
case class MarkData(
                     subj: String,
                     url: Option[String],
                     rating: Option[Double],
                     tags: Option[Set[String]],
                     comment: Option[String])

object MarkData {
  /** This auxiliary factory is used for the purpose of importing bookmarks only. */
  def apply(subj: String, url: String, tags: Set[String]): MarkData = MarkData(subj, Some(url), None, Some(tags), None)
}

case class MarkAux(hlights: Option[Seq[Highlight]],
                   tabVisible: Option[Seq[RangeMils]],
                   tabBground: Option[Seq[RangeMils]])

/**
  * User history (list) entry data model. An `Entry` is a `Mark` that belongs to a
  * particular user along with an ID and timestamp.
  *
  * `score` is not part of the documents in the database, but it is returned from
  * `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(
                 userId: UUID,
                 id: String,
                 mark: MarkData,
                 aux: MarkAux,
                 var urlPrfx: Option[mutable.WrappedArray[Byte]],
                 repId: Option[String],
                 from: Long,
                 thru: Long,
                 score: Option[Double] = None) {
  urlPrfx = mark.url map (_.prefx)

  def toJson: JsValue = {
    val d = new DateTime(from)
    Json.obj(
      Mark.ID -> id,
      "date" -> s"${d.year.getAsString}-${d.monthOfYear.getAsString}-${d.dayOfMonth.getAsString}",
      "rating" -> (Json toJson mark) (Mark.markDataJsonFormat))
  }
}

object Mark extends BSONHandlers {
  val ID_LENGTH: Int = 16
  val USER: String = nameOf[Mark](_.userId)
  val ID: String = nameOf[Mark](_.id)
  val MARK: String = nameOf[Mark](_.mark)
  val AUX: String = nameOf[Mark](_.aux)
  val UPRFX: String = nameOf[Mark](_.urlPrfx)
  val REPR: String = nameOf[Mark](_.repId)
  val MILS: String = nameOf[Mark](_.from)
  val THRU: String = nameOf[Mark](_.thru)
  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = nameOf[Mark](_.score)
  val SUBJ: String = nameOf[MarkData](_.subj)
  val URL: String = nameOf[MarkData](_.url)
  val STARS: String = nameOf[MarkData](_.rating)
  val TAGS: String = nameOf[MarkData](_.tags)
  val COMNT: String = nameOf[MarkData](_.comment)
  val HLGTS: String = nameOf[MarkAux](_.hlights)
  val TABVIS: String = nameOf[MarkAux](_.tabVisible)
  val TABBG: String = nameOf[MarkAux](_.tabBground)
  val HID: String = nameOf[Highlight](_.id)
  val POS: String = nameOf[Highlight](_.pos)
  val PATH: String = nameOf[HLPos](_.path)
  val TEXT: String = nameOf[HLPos](_.text)
  val INDX: String = nameOf[HLPos](_.indx)
  val TSTMP: String = nameOf[Highlight](_.from)
  val TILL: String = nameOf[Highlight](_.thru)
  implicit val hlposBsonHandler: BSONDocumentHandler[HLPos] = Macros.handler[HLPos]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
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
    MarkAux(None, None, None),
    None,
    rep,
    DateTime.now.getMillis,
    Long.MaxValue)
}
