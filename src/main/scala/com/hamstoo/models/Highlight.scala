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
                     timeThru: TimeStamp = INF_TIME) extends Annotation {

  import Highlight.fmt

  /** Used by backend's MarksController when producing full-page view and share email. */
  override def toFrontendJson: JsObject = Json.obj(
    "id" -> id,
    "preview" -> Json.toJson(preview),
    "type" -> "highlight"
  )

  /**
    * Used by backend's MarksController when producing JSON for the Chrome extension.  `pageCoord` may not be
    * required here; it's currently only used for sorting (in FPV and share emails), but we may start using it
    * in the Chrome extension for re-locating highlights and notes on the page.
    */
  import HighlightFormatters._
  def toExtensionJson: JsObject = Json.obj("id" -> id, "pos" -> pos) ++
    pageCoord.fold(Json.obj())(x => Json.obj("pageCoord" -> x))

  /**
    * Merges two positioning sequences and previews of two intersecting highlights.  At this point we know that
    * hlA appears before, and overlaps with, hlB.  This is different from how A and B are used in the other methods
    * of this class.
    */
  def union(hlB: Highlight): Highlight = {

    val hlA = this

    // look for longest paths sequence that is a tail of highlight A and a start of highlight B
    val intersection = hlB.pos.startsWith(hlA.pos).elements

    val tailB = hlB.pos.elements.drop(intersection.size - 1)

    // eA and eB are the same XPath node, so join them
    val eA = hlA.pos.elements.last
    val eB = tailB.head

    // java.lang.StringIndexOutOfBoundsException: begin 248, end 5, length 5
    val joinedText = eA.text + eB.text.substring(eA.index + eA.text.length - eB.index, eB.text.length)
    val joinedElem: Highlight.PositionElement = eA.copy(text = joinedText) // use eA's `index`

    // drop last element of highlight A (which could include only part of that element's text while highlight B is
    // guaranteed to include more) and first n - 1 intersecting elements of highlight B
    val posUnion = Highlight.Position(hlA.pos.elements.init ++ Seq(joinedElem) ++ tailB.tail)

    // Highlight.Positions have been stripped of some of their whitespace chars, e.g. '\n's, so try unioning
    // preview texts first before resorting to position texts
    val mbOverlap = hlA.preview.text.tails.find(hlB.preview.text.startsWith).filter(_.nonEmpty)
    val prvUnionTxt = mbOverlap.fold(posUnion.elements.foldLeft("")(_ + _.text)) { overlap =>
      hlA.preview.text + hlB.preview.text.substring(overlap.length)
    }

    val prvUnion = Highlight.Preview(hlA.preview.lead, prvUnionTxt, hlB.preview.tail)
    hlA.copy(pos = posUnion, preview = prvUnion, pageCoord = hlA.pageCoord.orElse(hlB.pageCoord))
  }
}

object Highlight extends BSONHandlers with AnnotationInfo {

  implicit val fmt: OFormat[Preview] = Json.format[Preview]

  /**
    * XML XPath and text located at that path.  `index` is the character index where the highlighted text
    * begins relative to the text of XPath **and all of its descendant nodes**.  So if we have the following HTML
    * `<p>Start<span>middle</span>finish</p>` and the user highlights "efin" then we'll have the following two
    * elements: [
    *   {"path": "body/p/span", "text": "e"  , "index": 5},
    *   {"path": "body/p"     , "text": "fin", "index": 11
    * ]
    *
    * See the following issue/comment for a description of neighbors/anchors/outerAnchors.
    *   https://github.com/Hamstoo/chrome-extension/issues/35#issuecomment-422162287
    */
  case class PositionElement(path: String,
                             text: String,
                             index: Int,
                             cssSelector: Option[String] = None,
                             neighbors: Option[Neighbors] = None,
                             anchors: Option[Anchors] = None,
                             outerAnchors: Option[Anchors] = None)

  case class Neighbors(left: Neighbor, right: Neighbor)
  case class Neighbor(path: String, cssSelector: String, elementText: String)
  case class Anchors(left: String, right: String)

  /** A highlight can stretch over a series of XPaths, hence the Sequence. */
  case class Position(elements: Seq[PositionElement]) extends Annotation.Position {
    def nonEmpty: Boolean = elements.nonEmpty

    /** Returns a new Highlight.Position consisting of elements of `first` that overlap w/ start of `second`. */
    def startsWith(first: Highlight.Position) = Highlight.Position {
      val second = this
      first.elements.tails.find { _.zip(second.elements).forall { case (f, s) => // first/second elements
        f.path == s.path &&
          f.index <= s.index && // first must start before second
          f.index + f.text.length >= s.index && // first must stop after or at where second starts (a.k.a. overlap)
          f.index + f.text.length <= s.index + s.text.length // first must stop before second (o/w second would be subseq)
      }}.get // rely on the empty tail always forall'ing to true if a non-empty tail does not
    }
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
  implicit val anchorsBsonHandler: BSONDocumentHandler[Anchors] = Macros.handler[Anchors]
  implicit val neighborBsonHandler: BSONDocumentHandler[Neighbor] = Macros.handler[Neighbor]
  implicit val neighborsBsonHandler: BSONDocumentHandler[Neighbors] = Macros.handler[Neighbors]
  implicit val hlposElemBsonHandler: BSONDocumentHandler[PositionElement] = Macros.handler[PositionElement]
  implicit val hlposBsonHandler: BSONDocumentHandler[Position] = Macros.handler[Position]
  implicit val hlprevBsonHandler: BSONDocumentHandler[Preview] = Macros.handler[Preview]
  implicit val highlightHandler: BSONDocumentHandler[Highlight] = Macros.handler[Highlight]
}

object HighlightFormatters {
  import com.hamstoo.models.ShareableFormatters._
  implicit val pageCoordJFmt: OFormat[PageCoord] = PageCoord.jFormat
  implicit val anchorsJFmt: OFormat[Highlight.Anchors] = Json.format[Highlight.Anchors]
  implicit val neighborJFmt: OFormat[Highlight.Neighbor] = Json.format[Highlight.Neighbor]
  implicit val neighborsJFmt: OFormat[Highlight.Neighbors] = Json.format[Highlight.Neighbors]
  implicit val hlPosElemJFmt: OFormat[Highlight.PositionElement] = Json.format[Highlight.PositionElement]
  implicit val hlPosJFmt: OFormat[Highlight.Position] = Json.format[Highlight.Position]
  implicit val hlPrevJFmt: OFormat[Highlight.Preview] = Json.format[Highlight.Preview]
  implicit val hlFmt: OFormat[Highlight] = Json.format[Highlight]
}
