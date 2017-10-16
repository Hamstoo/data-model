package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.generateDbId
import org.joda.time.DateTime
import play.api.libs.json.{JsObject, Json}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Data model of an inline note.  We refer to this as a "note" rather than a "comment" to help differentiate
  * between the two concepts, the latter being complementary user content.
  *
  * @param usrId    user UUID
  * @param id       inline note id, common for all versions through time
  * @param markId   markId of the web page where highlighting was done; URL can be obtained from there
  * @param pos      frontend comment data, including positioning and comment text
  * @param memeId   'comment representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class InlineNote(
                       usrId: UUID,
                       id: String = generateDbId(InlineNote.ID_LENGTH),
                       markId: String,
                       pos: InlineNotePosition,
                       pageCoord: Option[PageCoord] = None,
                       memeId: Option[String] = None,
                       timeFrom: Long = DateTime.now.getMillis,
                       timeThru: Long = Long.MaxValue) extends Content with Annotation with HasJsonPreview {

  override def jsonPreview: JsObject = Json.obj(
    "id" -> id,
    "preview" -> pos.text,
    "type" -> "comment"
  )
}

object InlineNote extends BSONHandlers with ContentInfo {

  val ID_LENGTH: Int = 16

  val PCOORD: String = nameOf[InlineNote](_.pageCoord)
  val PATH: String = nameOf[InlineNotePosition](_.path)
  val TEXT: String = nameOf[InlineNotePosition](_.text)
  val OFFSETX: String = nameOf[InlineNotePosition](_.offsetX)
  val OFFSETY: String = nameOf[InlineNotePosition](_.offsetY)
  implicit val commentposBsonHandler: BSONDocumentHandler[InlineNotePosition] = Macros.handler[InlineNotePosition]
  implicit val commentHandler: BSONDocumentHandler[InlineNote] = Macros.handler[InlineNote]
}
