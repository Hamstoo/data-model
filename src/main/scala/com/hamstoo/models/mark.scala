package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.{ExtendedString, fieldName}
import org.joda.time.DateTime
import reactivemongo.bson.{BSONBinary, BSONDocumentHandler, BSONHandler, BSONString, Macros, Subtype}

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
}

object Mark extends BSONHandlers {
  val ID_LENGTH: Int = 16
  val USER: String = fieldName[Mark]("userId")
  val ID: String = fieldName[Mark]("id")
  val MARK: String = fieldName[Mark]("mark")
  val AUX: String = fieldName[Mark]("aux")
  val UPRFX: String = fieldName[Mark]("urlPrfx")
  val REPR: String = fieldName[Mark]("repId")
  val MILS: String = fieldName[Mark]("from")
  val THRU: String = fieldName[Mark]("thru")
  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = fieldName[Mark]("score")
  val SUBJ: String = fieldName[MarkData]("subj")
  val URL: String = fieldName[MarkData]("url")
  val STARS: String = fieldName[MarkData]("rating")
  val TAGS: String = fieldName[MarkData]("tags")
  val COMNT: String = fieldName[MarkData]("comment")
  val HLGTS: String = fieldName[MarkAux]("hlights")
  val TABVIS: String = fieldName[MarkAux]("tabVisible")
  val TABBG: String = fieldName[MarkAux]("tabBground")
  val HID: String = fieldName[Highlight]("id")
  val POS: String = fieldName[Highlight]("pos")
  val PATH: String = fieldName[HLPos]("path")
  val TEXT: String = fieldName[HLPos]("text")
  val INDX: String = fieldName[HLPos]("indx")
  val TSTMP: String = fieldName[Highlight]("from")
  val TILL: String = fieldName[Highlight]("thru")
  implicit val hlposBsonHandler: BSONDocumentHandler[HLPos] = Macros.handler[HLPos]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val auxBsonHandler: BSONDocumentHandler[MarkAux] = Macros.handler[MarkAux]
  implicit val markBsonHandler: BSONDocumentHandler[MarkData] = Macros.handler[MarkData]
  implicit val entryBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]

  /** Factory with ID and timestamp generation. */
  def apply(userId: UUID, mark: MarkData): Mark = Mark(
    userId,
    Random.alphanumeric take ID_LENGTH mkString,
    mark,
    MarkAux(None, None, None),
    None,
    None,
    DateTime.now.getMillis,
    Long.MaxValue)
}
