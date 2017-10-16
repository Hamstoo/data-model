package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.generateDbId
import org.joda.time.DateTime
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Data model of a text highlight.
  *
  * See `getHighlighted` function at src/content-scripts/annotation/highlight/highlight.js in chrome-extension repo.
  *
  * @param usrId    owner UUID
  * @param id       highlight id, common for all versions
  * @param markId   markId of the web page where highlighting was done; URL can be obtained from there
  * @param pos      array of positioning data and initial element text index
  * @param preview  highlight preview for full page view
  * @param memeId   'highlight representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class Highlight(
                      usrId: UUID,
                      id: String = generateDbId(Highlight.ID_LENGTH),
                      markId: String,
                      pos: HLPosition,
                      pageCoord: Option[PageCoord] = None,
                      preview: Highlight.Preview,
                      memeId: Option[String] = None,
                      timeFrom: Long = DateTime.now.getMillis,
                      timeThru: Long = Long.MaxValue) extends Content with Annotation with HasJsonPreview {
  import Highlight.fmt

  override def jsonPreview: JsObject = Json.obj(
    "id" -> id,
    "preview" -> Json.toJson(preview),
    "type" -> "highlight"
  )
}

object Highlight extends BSONHandlers with ContentInfo {

  implicit val fmt: OFormat[Preview] = Json.format[Preview]

  /** Text that occurs before and after the highlighted text, along with the highlighted `text` itself. */
  case class Preview(lead: String, text: String, tail: String)

  val ID_LENGTH: Int = 16

  val PCOORD: String = nameOf[Highlight](_.pageCoord)
  val PATH: String = nameOf[HLPositionElement](_.path)
  val TEXT: String = nameOf[HLPositionElement](_.text)
  val INDX: String = nameOf[HLPosition](_.initIndex)
  val PRVW: String = nameOf[Highlight](_.preview)
  val LEAD: String = nameOf[Preview](_.lead)
  val PTXT: String = nameOf[Preview](_.text)
  val TAIL: String = nameOf[Preview](_.tail)

  assert(nameOf[Highlight](_.timeFrom) == com.hamstoo.models.Mark.TIMEFROM)
  assert(nameOf[Highlight](_.timeThru) == com.hamstoo.models.Mark.TIMETHRU)
  implicit val hlposElemBsonHandler: BSONDocumentHandler[HLPositionElement] = Macros.handler[HLPositionElement]
  implicit val hlposBsonHandler: BSONDocumentHandler[HLPosition] = Macros.handler[HLPosition]
  implicit val hlprevBsonHandler: BSONDocumentHandler[Preview] = Macros.handler[Preview]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
}
