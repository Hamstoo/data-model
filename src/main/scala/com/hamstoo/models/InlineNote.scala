/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ExtendedOption, INF_TIME, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import play.api.libs.json.{JsObject, Json, OFormat}
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
  * @param pageNumber  See description of this parameter in Highlight.scala.
  */
case class InlineNote(usrId: UUID,
                      sharedWith: Option[SharedWith] = None,
                      nSharedFrom: Option[Int] = Some(0),
                      nSharedTo: Option[Int] = Some(0),
                      id: ObjectId = generateDbId(InlineNote.ID_LENGTH),
                      markId: ObjectId,
                      pos: InlineNote.Position,
                      anchors: Option[Seq[InlineNote.Anchor]] = None,
                      pageCoord: Option[PageCoord] = None,
                      pageNumber: Option[Int] = None,
                      memeId: Option[String] = None,
                      timeFrom: TimeStamp = TIME_NOW,
                      timeThru: TimeStamp = INF_TIME) extends Annotation {

  /** Used by backend's MarksController when producing full-page view and share email. */
  override def toFrontendJson: JsObject = super.toFrontendJson ++ Json.obj("preview" -> pos.text, "type" -> "comment")

  /**
    * Used by backend's MarksController when producing JSON for the Chrome extension.  `pageCoord` may not be
    * required here; it's currently only used for sorting (in FPV and share emails), but we may start using it
    * in the Chrome extension for re-locating highlights and notes on the page.
    */
  import InlineNoteFormatters._
  override def toExtensionJson(implicit callingUserId: UUID) =
    super.toExtensionJson ++
    Json.obj("pos" -> pos) ++
    anchors.toJson("anchors")
}

object InlineNote extends BSONHandlers with AnnotationInfo {

  /**
    * Data class containing frontend comment data, that is directly serialised into and deserialized from JSON. Data
    * stored here is completely generated by frontend.
    * @param text     Note text.  This is the user's note.  As of 2018-8-4 inline note positioning refactoring
    *                 (chrome-extension issue #45) this field should probably reside inside InlineNote object rather
    *                 than Position, but we kept it here for easier backwards compatibility.  This optional field will
    *                 be absent from Anchors' Positions.
    * @param path     X-path
    * @param cssSelector  New field as of 2018-8-4 inline note positioning refactoring (chrome-extension issue #45).
    *                     First we try to locate comment by XPath, then if it fails, by CSS, and if it fails as well,
    *                     then we're searching for anchors the same way (Xpath then CSS). So we have at least six
    *                     elements to help us position the comment (one `pos` and five `anchors`).
    * @param nodeValue  This is the text (?) in the X-path node, which, along with cssSelector, is used for
    *                   re-locating notes when page HTML changes.  New field as of 2018-8-4 inline note positioning
    *                   refactoring (chrome-extension issue #45).
    * @param offsetX  Horizontal offset inside path element. (?)
    * @param offsetY  Vertical offset inside path element. (?)
    */
  case class Position(text: Option[String],
                      path: String,
                      cssSelector: Option[String],
                      nodeValue: Option[String],
                      offsetX: Double,
                      offsetY: Double) extends Annotation.Position {

    /** Coordinates (offset) of an inline note in a node.  Useful for sorting. */
    def nodeCoord = PageCoord(offsetX, offsetY)
  }

  /**
    * If we fail to find element from `InlineNote.pos` both with XPath and CSS selector, then anchors come
    * into play.  To deal with decreased accuracy we normalize comment's position by getting it's coordinates
    * based on several anchors, and then finding the centroid of the results.  I've tested it with five anchors
    * and it works very precisely.
    * New data structure as of 2018-8-4 inline note positioning refactoring (chrome-extension issue #45).
    * @param position  I moved anchor position properties inside the field position to keep it consistent with
    *                  main comment data structure, and it might be convenient if we'll need to add more fields
    *                  to anchors in future.
    */
  case class Anchor(position: Position)

  val ID_LENGTH: Int = 16

  val PATH: String = nameOf[Position](_.path)
  val TEXT: String = nameOf[Position](_.text)
  val OFFSETX: String = nameOf[Position](_.offsetX)
  val OFFSETY: String = nameOf[Position](_.offsetY)
  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val commentposBsonHandler: BSONDocumentHandler[Position] = Macros.handler[Position]
  implicit val _: BSONDocumentHandler[Anchor] = Macros.handler[Anchor]
  implicit val commentHandler: BSONDocumentHandler[InlineNote] = Macros.handler[InlineNote]
}

object InlineNoteFormatters {
  implicit val pageCoordJFmt: OFormat[PageCoord] = PageCoord.jFormat
  implicit val posJFmt: OFormat[InlineNote.Position] = Json.format[InlineNote.Position]
  implicit val anchorJFmt: OFormat[InlineNote.Anchor] = Json.format[InlineNote.Anchor]
}
