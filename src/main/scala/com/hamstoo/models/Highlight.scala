package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.models.Highlight.{HLPos, HLPreview}
import com.hamstoo.utils.ExtendedString
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

/**
  * Data model of a text highlight.
  *
  * @param usrId    owner UUID
  * @param id       highlight id, common for all versions
  * @param url      URL of the web page where highlighting was done
  * @param uPref    binary prefix of the URL for indexing; set by class init
  * @param pos      array of positioning data
  * @param preview  highlight preview for full page view
  * @param memeId   'highlight representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class Highlight(
                      usrId: UUID,
                      id: String = Random.alphanumeric take Highlight.ID_LENGTH mkString,
                      url: String,
                      var uPref: Option[mutable.WrappedArray[Byte]] = None,
                      pos: Seq[HLPos],
                      preview: HLPreview,
                      memeId: Option[String] = None,
                      timeFrom: Long = DateTime.now.getMillis,
                      timeThru: Long = Long.MaxValue) {
  uPref = Some(url.prefx)
}

object Highlight extends BSONHandlers {

  case class HLPos(path: String, text: String, indx: Int)

  case class HLPreview(lead: String, text: String, tail: String)

  val ID_LENGTH: Int = 16
  val USR: String = nameOf[Highlight](_.usrId)
  val ID: String = nameOf[Highlight](_.id)
  val POS: String = nameOf[Highlight](_.pos)
  val PATH: String = nameOf[HLPos](_.path)
  val TEXT: String = nameOf[HLPos](_.text)
  val INDX: String = nameOf[HLPos](_.indx)
  val URL: String = nameOf[Highlight](_.url)
  val UPRF: String = nameOf[Highlight](_.uPref)
  val PRVW: String = nameOf[Highlight](_.preview)
  val LEAD: String = nameOf[HLPreview](_.lead)
  val PTXT: String = nameOf[HLPreview](_.text)
  val TAIL: String = nameOf[HLPreview](_.tail)
  val MEM: String = nameOf[Highlight](_.memeId)
  val TSTMP: String = nameOf[Highlight](_.timeFrom)
  val TILL: String = nameOf[Highlight](_.timeThru)
  implicit val hlposBsonHandler: BSONDocumentHandler[HLPos] = Macros.handler[HLPos]
  implicit val hlprevBsonHandler: BSONDocumentHandler[HLPreview] = Macros.handler[HLPreview]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
}
