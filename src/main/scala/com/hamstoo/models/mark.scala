package com.hamstoo.models

import java.util.UUID

import reactivemongo.bson.{BSONBinary, BSONDocumentHandler, BSONHandler, BSONString, Macros, Subtype}
import com.hamstoo.utils.fieldName
import org.joda.time.DateTime
import com.hamstoo.utils.ExtendedString

import scala.collection.mutable
import scala.util.Random

case class RangeMils(begin: Long, end: Long)

/**
  * Data model of a text highlight. The fields are:
  * - path - the CSS XPath of HTML element used to locate the highlight;
  * - text - the highlighted piece of text;
  * - indx - the number of the first character in the selection relative to the whole string in HTML element;
  * - mils - timestamp.
  */
case class Highlight(id: String, path: String, text: String, indx: Int, from: Long, thru: Long)

object Highlight {
  val HID: String = fieldName[Highlight]("id")
  val PATH: String = fieldName[Highlight]("path")
  val TEXT: String = fieldName[Highlight]("text")
  val INDX: String = fieldName[Highlight]("indx")
  val TSTMP: String = fieldName[Highlight]("from")
  val TILL: String = fieldName[Highlight]("thru")
}

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
case class Mark(
                 subj: String,
                 url: Option[String],
                 var urlPrfx: Option[mutable.WrappedArray[Byte]],
                 repId: Option[String],
                 rating: Option[Double],
                 tags: Option[Set[String]],
                 comment: Option[String],
                 hlights: Option[Seq[Highlight]],
                 tabVisible: Option[Seq[RangeMils]],
                 tabBground: Option[Seq[RangeMils]]) {
  urlPrfx = url.map(_.prefx)
}

object Mark {
  // JSON deserialization field names
  val SUBJ: String = fieldName[Mark]("subj")
  val URL: String = fieldName[Mark]("url")
  val UPRFX: String = fieldName[Mark]("urlPrfx")
  val REPR: String = fieldName[Mark]("repId")
  val STARS: String = fieldName[Mark]("rating")
  val TAGS: String = fieldName[Mark]("tags")
  val COMMENT: String = fieldName[Mark]("comment")
  val HLGTS: String = fieldName[Mark]("hlights")
  val TABVIS: String = fieldName[Mark]("tabVisible")
  val TABBG: String = fieldName[Mark]("tabBground")
  implicit val arrayBsonHandler: BSONHandler[BSONBinary, mutable.WrappedArray[Byte]] =
    BSONHandler[BSONBinary, mutable.WrappedArray[Byte]](
      _.byteArray,
      a => BSONBinary(a.array, Subtype.GenericBinarySubtype))
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
  implicit val markBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]

  /** This auxiliary factory is used for the purpose of importing bookmarks only. */
  def apply(subj: String, url: String, tags: Set[String]): Mark =
    Mark(subj, Some(url), None, None, None, Some(tags), None, None, None, None)
}

/**
  * User history (list) entry data model. An `Entry` is a `Mark` that belongs to a
  * particular user along with an ID and timestamp.
  *
  * `score` is not part of the documents in the database, but it is returned from
  * `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Entry(userId: UUID, id: String, from: Long, thru: Long, mark: Mark, score: Option[Double] = None)

object Entry {
  val ID_LENGTH = 16
  // JSON deserialization field names
  val USER: String = fieldName[Entry]("userId")
  val ID: String = fieldName[Entry]("id")
  val MILS: String = fieldName[Entry]("from")
  val THRU: String = fieldName[Entry]("thru")
  val MARK: String = fieldName[Entry]("mark")
  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = fieldName[Entry]("score")
  implicit val uuidBsonHandler: BSONHandler[BSONString, UUID] =
    BSONHandler[BSONString, UUID](UUID fromString _.value, BSONString apply _.toString)
  implicit val entryBsonHandler: BSONDocumentHandler[Entry] = Macros.handler[Entry]

  /** Factory with ID and timestamp generation. */
  def apply(userId: UUID, mark: Mark): Entry =
    Entry(userId, Random.alphanumeric.take(ID_LENGTH).mkString, DateTime.now.getMillis, Long.MaxValue, mark)
}
