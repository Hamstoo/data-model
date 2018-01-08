package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{generateDbId, INF_TIME, TIME_NOW}
import play.api.libs.json.{JsObject, Json}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Data model of an inline note.  We refer to this as a "note" rather than a "comment" to help differentiate
  * between the two concepts, the latter being complementary user content.
  *
  * @param usrId    user UUID
  * @param sharedWith  defines which other users are allowed to read or write this InlineNote
  * @param id       inline note id, common for all versions through time
  * @param markId   markId of the web page where highlighting was done; URL can be obtained from there
  * @param pos      frontend comment data, including positioning and comment text
  * @param memeId   'comment representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class InlineNote(
                       usrId: UUID,
                       sharedWith: Option[SharedWith] = None,
                       nSharedFrom: Option[Int] = Some(0),
                       nSharedTo: Option[Int] = Some(0),
                       id: String = generateDbId(InlineNote.ID_LENGTH),
                       markId: String,
                       pos: InlineNote.Position,
                       pageCoord: Option[PageCoord] = None,
                       memeId: Option[String] = None,
                       timeFrom: Long = TIME_NOW,
                       timeThru: Long = INF_TIME) extends Annotation with HasJsonPreview {

  override def jsonPreview: JsObject = Json.obj(
    "id" -> id,
    "preview" -> pos.text,
    "type" -> "comment"
  )
}

object InlineNote extends BSONHandlers with AnnotationInfo {

  /**
    * Data class containing frontend comment data, that is directly serialised into and deserialized from JSON. Data
    * stored here is completely generated by frontend.
    * @param path     X-path
    * @param text     Note text
    * @param offsetX  Horizontal offset inside path element. (?)
    * @param offsetY  Vertical offset inside path element. (?)
    */
  case class Position(path: String,
                      text: String,
                      offsetX: Double,
                      offsetY: Double) extends Positions

  val ID_LENGTH: Int = 16

  val PATH: String = nameOf[Position](_.path)
  val TEXT: String = nameOf[Position](_.text)
  val OFFSETX: String = nameOf[Position](_.offsetX)
  val OFFSETY: String = nameOf[Position](_.offsetY)
  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val commentposBsonHandler: BSONDocumentHandler[Position] = Macros.handler[Position]
  implicit val commentHandler: BSONDocumentHandler[InlineNote] = Macros.handler[InlineNote]
}
