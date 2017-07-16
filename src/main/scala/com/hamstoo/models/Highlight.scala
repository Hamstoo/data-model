package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.models.Highlight.HLPos
import com.hamstoo.utils.ExtendedString
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

/**
  * Data model of a text highlight.
  *
  * @param id       highlight id
  * @param pos      array of positioning data
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class Highlight(
                      usrId: UUID,
                      id: String,
                      url: String,
                      var uPref: Option[mutable.WrappedArray[Byte]],
                      pos: Seq[HLPos],
                      memeId: Option[String],
                      timeFrom: Long,
                      timeThru: Long) {
  uPref = Some(url.prefx)
}

object Highlight extends BSONHandlers {

  case class HLPos(path: String, text: String, indx: Int)

  val ID_LENGTH: Int = 16
  val USR: String = nameOf[Highlight](_.usrId)
  val ID: String = nameOf[Highlight](_.id)
  val POS: String = nameOf[Highlight](_.pos)
  val PATH: String = nameOf[HLPos](_.path)
  val TEXT: String = nameOf[HLPos](_.text)
  val INDX: String = nameOf[HLPos](_.indx)
  val URL: String = nameOf[Highlight](_.url)
  val UPRF: String = nameOf[Highlight](_.uPref)
  val MEM: String = nameOf[Highlight](_.memeId)
  val TSTMP: String = nameOf[Highlight](_.timeFrom)
  val TILL: String = nameOf[Highlight](_.timeThru)
  implicit val hlposBsonHandler: BSONDocumentHandler[HLPos] = Macros.handler[HLPos]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]

  /** Factory with ID and timestamp generation. */
  def apply(usr: UUID, pos: Seq[HLPos], url: String): Highlight = Highlight(
    usr,
    Random.alphanumeric take ID_LENGTH mkString,
    url,
    None,
    pos,
    None,
    DateTime.now.getMillis,
    Long.MaxValue)
}