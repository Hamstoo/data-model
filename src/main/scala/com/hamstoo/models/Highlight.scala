package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{INF_TIME, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Data model of a text highlight.
  *
  * See `getHighlighted` function at src/content-scripts/annotation/highlight/highlight.js in chrome-extension repo.
  *
  * @param usrId    owner UUID
  * @param sharedWith  defines which other users are allowed to read or write this Highlight
  * @param id       highlight id, common for all versions
  * @param markId   markId of the web page where highlighting was done; URL can be obtained from there
  * @param pos      array of positioning data and initial element text index
  * @param preview  highlight preview for full page view
  * @param memeId   'highlight representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class Highlight(usrId: UUID,
                     sharedWith: Option[SharedWith] = None,
                     nSharedFrom: Option[Int] = Some(0),
                     nSharedTo: Option[Int] = Some(0),
                     id: ObjectId = generateDbId(Highlight.ID_LENGTH),
                     markId: ObjectId,
                     pos: Highlight.Position,
                     pageCoord: Option[PageCoord] = None,
                     preview: Highlight.Preview,
                     memeId: Option[String] = None,
                     timeFrom: TimeStamp = TIME_NOW,
                     timeThru: TimeStamp = INF_TIME) extends Annotation with HasJsonPreview {

  import Highlight.fmt

  override def jsonPreview: JsObject = Json.obj(
    "id" -> id,
    "preview" -> Json.toJson(preview),
    "type" -> "highlight"
  )
}

object Highlight extends BSONHandlers with AnnotationInfo {

  implicit val fmt: OFormat[Preview] = Json.format[Preview]

  /**
    * XML XPath and text located at that path.  `index` is the character index where the highlighted text
    * begins relative to the text of XPath *and all of its descendant nodes*.  So if we have the following HTML
    * `<p>Start<span>middle</span>finish</p>` and the user highlights "efin" then we'll have the following two
    * elements: [
    *   {"path": "body/p/span", "text": "e"  , "index": 5},
    *   {"path": "body/p"     , "text": "fin", "index": 11
    * ]
    */
  case class PositionElement(path: String, text: String, index: Int)

  /** A highlight can stretch over a series of XPaths. */
  case class Position(elements: Seq[PositionElement]) extends Positions {
    def nonEmpty: Boolean = elements.nonEmpty
  }

  /** Text that occurs before and after the highlighted text, along with the highlighted `text` itself. */
  case class Preview(lead: String, text: String, tail: String)

  val ID_LENGTH: Int = 16

  val PATH: String = nameOf[PositionElement](_.path)
  val TEXT: String = nameOf[PositionElement](_.text)
  val PRVW: String = nameOf[Highlight](_.preview)
  val LEAD: String = nameOf[Preview](_.lead)
  val PTXT: String = nameOf[Preview](_.text)
  val TAIL: String = nameOf[Preview](_.tail)

  assert(nameOf[Highlight](_.timeFrom) == com.hamstoo.models.Mark.TIMEFROM)
  assert(nameOf[Highlight](_.timeThru) == com.hamstoo.models.Mark.TIMETHRU)
  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val hlposElemBsonHandler: BSONDocumentHandler[PositionElement] = Macros.handler[PositionElement]
  implicit val hlposBsonHandler: BSONDocumentHandler[Position] = Macros.handler[Position]
  implicit val hlprevBsonHandler: BSONDocumentHandler[Preview] = Macros.handler[Preview]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
}
