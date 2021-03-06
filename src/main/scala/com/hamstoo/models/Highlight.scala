/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ExtendedOption, INF_TIME, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import play.api.Logger
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.util.matching.Regex

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
  * @param pageNumber  Annotations recovery might produce corrupted data for PDF files. It happens because of the way
  *                    PDF.js renders the pages.  It loads pages progressively as the user navigates through pages.
  *                    So when extension tries to display annotation (both highlight and comment) on the page that
  *                    wasn't loaded yet, it can't find the annotation and tries to recover it.  To prevent that from
  *                    happening we might add 'pageNumber' field for PDF annotations--to display annotations only when
  *                    its page is loaded. [https://github.com/Hamstoo/chrome-extension/issues/55]
  * @param endPageNumber  Highlights, unlike inline notes, can extend over multiple pages.
  * @param notFound  This field gets set to true by chrome-extension when we fail to locate a highlight upon
  *                  returning to the URL on which it was originally produced.  Setting this to true lets the
  *                  extension know that it doesn't have to repeatedly re-save the page source, which we would
  *                  otherwise typically do when attempting to re-locate a highlight.
  */
case class Highlight(usrId: UUID,
                     sharedWith: Option[SharedWith] = None,
                     nSharedFrom: Option[Int] = Some(0),
                     nSharedTo: Option[Int] = Some(0),
                     id: ObjectId = generateDbId(Highlight.ID_LENGTH),
                     markId: ObjectId,
                     pos: Highlight.Position,
                     pageCoord: Option[PageCoord] = None,
                     pageNumber: Option[Int] = None,
                     endPageNumber: Option[Int] = None,
                     windowName: Option[String] = None,
                     preview: Highlight.Preview,
                     memeId: Option[String] = None,
                     notFound: Option[Boolean] = None,
                     timeFrom: TimeStamp = TIME_NOW,
                     timeThru: TimeStamp = INF_TIME) extends Annotation {

  import Highlight.logger
  import HighlightFormatters._

  /** Used by backend's MarksController when producing full-page view and share email. */
  override def toFrontendJson: JsObject = super.toFrontendJson ++ Json.obj("preview" -> preview, "type" -> "highlight")
  override def getText: String = preview.text

  /**
    * Used by backend's MarksController when producing JSON for the Chrome extension.  `pageCoord` may not be
    * required here; it's currently only used for sorting (in FPV and share emails), but we may start using it
    * in the Chrome extension for re-locating highlights and notes on the page.
    */
  override def toExtensionJson(implicit callingUserId: UUID): JsObject =
    super.toExtensionJson ++
    Json.obj("pos" -> pos, "preview" -> preview) ++
    endPageNumber.toJsOption("endPageNumber") ++
    notFound.toJsOption("notFound")

  /** Defer to Highlight.Position. */
  def mergeSameElems(): Highlight = copy(pos = pos.mergeSameElems(preview.text))
  def startsWith(first: Highlight): Seq[Highlight.PositionElement] = pos.startsWith(first.pos)
  def isSubseq(outer: Highlight): Boolean = pos.isSubseq(outer.pos)

  /**
    * Merges two positioning sequences and previews of two intersecting highlights.  At this point we know that
    * hlA appears before, and overlaps with, hlB.  This is different from how A and B are used in the other methods
    * of this class.
    */
  def union(hlB: Highlight, mbIntersection: Option[Seq[Highlight.PositionElement]] = None): Highlight = {

    val hlA = this

    // look for longest paths sequence that is a tail of highlight A and a start of highlight B
    // (optionally use a passed-in intersection to ensure same is being used everywhere expected)
    val intersection = mbIntersection getOrElse { hlB.startsWith(hlA) }
    assert(intersection.nonEmpty)

    if (intersection.size <= 0 || intersection.size - 1 >= hlB.pos.elements.size)
      logger.error(s"Invalid intersection size ${intersection.size} (hlB.pos.elements.size=${hlB.pos.elements.size}) for highlight positions ${hlA.pos} ... and ... ${hlB.pos} ")
    val tailB = hlB.pos.elements.drop(intersection.size - 1)

    // elemA and elemB are the same XPath node, so merge them
    val elemA = hlA.pos.elements.last
    val elemB = tailB.head // java.util.NoSuchElementException: head of empty list

    // Highlight.Positions have been stripped of some of their whitespace chars, e.g. '\n's, so try unioning
    // preview texts first before resorting to position texts
    val mbOverlap = hlA.preview.text.tails.find(hlB.preview.text.startsWith).filter(_.nonEmpty)

    val prvUnionTxt = mbOverlap
      .fold {
        val mergedElem0 = elemA.merge(elemB, "") // using empty string b/c no prvUnionText to be had yet at this point

        // drop last element of highlight A (which could include only part of that element's text while highlight B is
        // guaranteed to include more) and first n - 1 intersecting elements of highlight B
        val posUnion0 = Highlight.Position(hlA.pos.elements.init ++ Seq(mergedElem0) ++ tailB.tail)
        posUnion0.elements.foldLeft("")(_ + _.text)

      }{ overlap =>

        // we know that highlight B occurs after A in the document with some overlap, and if the user created B after
        // A, then B might start with two '\n' chars as a result of this `selectedText = selected.toString();` (in
        // chrome-extension's annotations.js) which seems to detect these chars following existing highlights
        //   [https://github.com/Hamstoo/chrome-extension/issues/35#issuecomment-422840050]
        val subPrevB0 = hlB.preview.text.substring(overlap.length)
        val subPrevB = if (subPrevB0.startsWith("\n\n")) subPrevB0.substring(2) else subPrevB0

        hlA.preview.text + subPrevB
      }

    // use prvUnionTxt to help fill in any missing chars from the pos.elements.texts
    val mergedElem = elemA.merge(elemB, prvUnionTxt)
    val posUnion = Highlight.Position(hlA.pos.elements.init ++ Seq(mergedElem) ++ tailB.tail)

    val prvUnion = Highlight.Preview(hlA.preview.lead, prvUnionTxt, hlB.preview.tail)
    hlA.copy(pos = posUnion, preview = prvUnion, pageCoord = hlA.pageCoord.orElse(hlB.pageCoord))
  }

  /** In this `fromExtensionJson` "position" field can be absent from incoming JSON. */
  override def mergeExtensionJson(json: JsObject): Annotation = {
    import com.hamstoo.models.HighlightFormatters._
    val posJson: JsObject = (json \ "position").toOption.toJsOption(Highlight.POS)
    Json.toJsObject(this).deepMerge(json - "position" ++ posJson).as[Highlight]
  }
}

object Highlight extends BSONHandlers with AnnotationInfo {

  val logger = Logger(getClass)

  /** Translate from incoming API JSON; rename "position" field to "pos" and add in `usrId` and `markId` fields. */
  def fromExtensionJson(json: JsObject, userId: UUID, markId: ObjectId): Highlight = {
    import com.hamstoo.models.HighlightFormatters._
    val base = Highlight(userId, markId = markId,
                         pos = json("position").as[Highlight.Position],
                         preview = json(Highlight.PRVW).as[Highlight.Preview])
    base.mergeExtensionJson(json).asInstanceOf[Highlight]
  }

  /**
    * XML XPath and text located at that path.  `index` is the character index where the highlighted text
    * begins relative to the text of XPath **and all of its descendant nodes**.  So if we have the following HTML
    * `<p>Start<span>middle</span>finish</p>` and the user highlights "efin" then we'll have the following two
    * elements: [
    *   {"path": "body/p/span", "text": "e"  , "index": 5},
    *   {"path": "body/p"     , "text": "fin", "index": 11
    * ]
    *
    * > > I realize we don't do it this way--it might be too slow, for example--but it would be possible.
    * > Yeah, it's possible to check if page has this text, but in order to find which exact elements contain which
    * > part of highlight, we'll have to go through so many elements that it will significantly slow down the process.
    * > That's exactly why we use paths and selectors - to make a short list of elements where to look
    *    https://github.com/Hamstoo/chrome-extension/issues/35#issuecomment-424015773
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
                             outerAnchors: Option[Anchors] = None) {

    /** Union two overlapping or edge-touching PositionElements. */
    def merge(eB: PositionElement, previewText: String): PositionElement = {
      val eA = this

      // if this function is defined with T and U as type parameters of the same function (as opposed to a closure)
      // then T cannot be inferred by the compiler and has to be provided explicitly
      def joinLeftRightOpts[T] = {
        import scala.language.reflectiveCalls
        def f[U <: {def left : T; def right : T}](mbA: Option[U], mbB: Option[U]): Option[(T, T)] = {
          val mbLeft = mbA.map(_.left).orElse(mbB.map(_.left)) // take left first from A
          val mbRight = mbB.map(_.right).orElse(mbA.map(_.right)) // take right first from B
          if (mbLeft.isDefined) Some(mbLeft.get, mbRight.get) else None // if either isDefined then both must be
        }
        f _
      }

      val mgNeighbors = joinLeftRightOpts(eA.neighbors   , eB.neighbors   ).map((Highlight.Neighbors.apply _).tupled)
      val mgAnchors   = joinLeftRightOpts(eA.anchors     , eB.anchors     ).map((Highlight.  Anchors.apply _).tupled)
      val mgOAnchors  = joinLeftRightOpts(eA.outerAnchors, eB.outerAnchors).map((Highlight.  Anchors.apply _).tupled)

      // to prevent the below StringIndexOutOfBoundsException: if eB starts with a sequence of '\n's, its index
      // could be too large by that many chars (which may be due to missing/phantom \n chars at the end of eA.text)
      //   [https://github.com/Hamstoo/chrome-extension/issues/35#issuecomment-423266260]
      //val indexPad = (4 to 0 by -1) find { i => eB.text.startsWith("\n" * i) } getOrElse 0
      val startB = eA.index + eA.text.length - eB.index
      val indexPad = if (startB < 0) -startB else 0

      // use previewText to try to figure out what the missing whitespace chars are (see comment in
      // chrome-extension's annotations.js)
      val pad = if (indexPad == 0) "" else {

        // Regex.quote doesn't properly quote '\n's (nor '\t's, I'm guessing) and since we're really only interested
        // in missing whitespace, performing these substitutions should be okay (using uppercase subs even safer)
        val substitutes = Map("\n" -> "N", "\t" -> "T")
        val textA :: textB :: prvText :: Nil = Seq(eA.text, eB.text, previewText)
          .map { t => substitutes.foldLeft(t) { case (ti, (k, v)) => ti.replaceAll(k, v) } }

        val rgx = (Regex.quote(textA) + s"(.{$indexPad})" + Regex.quote(textB)).r
        rgx.findFirstMatchIn(prvText).map { mtch =>
          val pad = substitutes.foldLeft(mtch.group(1)) { case (ti, (k, v)) => ti.replaceAll(v, k) }
          logger.warn(s"Derived $indexPad-char pad string '$pad' from '$rgx'")
          pad
        }.getOrElse(" " * indexPad)
      }

      // java.lang.StringIndexOutOfBoundsException: begin 248, end 5, length 5 (before 2018.9.19, see above)
      val log = if (indexPad == 0) { logger.debug(_: String) } else { logger.warn(_: String) }
      log(s"PositionElement.merge = '${eA.text}' + '$pad' + '${eB.text}'.substring(${eA.index} + ${eA.text.length} - ${eB.index} + $indexPad, ${eB.text.length})")
      val mergedText = eA.text + pad + eB.text.substring(eA.index + eA.text.length - eB.index + indexPad, eB.text.length)
      log(s"PositionElement.merge == '$mergedText'")

      // use eA's `index` and `cssSelector`
      eA.copy(text = mergedText, neighbors = mgNeighbors, anchors = mgAnchors, outerAnchors = mgOAnchors)
    }
  }

  case class Neighbors(left: Neighbor, right: Neighbor)
  case class Neighbor(path: String, cssSelector: String, elementText: String)
  case class Anchors(left: String, right: String)

  /** A highlight can stretch over a series of XPaths, hence the Sequence. */
  case class Position(elements: Seq[PositionElement]) extends Annotation.Position {

    /** Returns true if the Position's elements sequence is nonEmpty. */
    def nonEmpty: Boolean = elements.nonEmpty

    /** Recursively joins same-XPath-elements to ensure there are no consecutive elements with the same XPath. */
    def mergeSameElems(previewText: String, acc: Position = Position(Nil)): Position = {
      if (elements.size < 2) Position(acc.elements ++ elements)
      else {
        val t = elements.tail

        // if first 2 paths in the list are the same, then merge/union them and prepend them to the remaining tail
        if (elements.head.path == t.head.path)
          Position(elements.head.merge(t.head, previewText) +: t.tail).mergeSameElems(previewText, acc)
        else
          Position(t).mergeSameElems(previewText, Position(acc.elements :+ elements.head))
      }
    }

    /** Returns a new Highlight.Position consisting of elements of `first` that overlap w/ start of `second`. */
    def startsWith(first: Highlight.Position): Seq[Highlight.PositionElement] = {
      val second = this
      // `forall` here will restrict `second.elements` to the same size as the current tail of `first.elements`
      first.elements.tails.find { _.zip(second.elements)
        .forall { case (f, s) => // first/second elements
          f.path == s.path &&
          f.index <= s.index && // first must start before second
          f.index + f.text.length >= s.index && // first must stop after or at where second starts (a.k.a. overlap)
          f.index + f.text.length <= s.index + s.text.length // first must stop before second ends (o/w second would be subseq)
        }

      // okay to call `get` b/c we're relying on the empty tail always forall'ing to true (if a non-empty tail does not)
      }.get
    }

    /** Very similar to ExtendedPosition0.startsWith, but some important minor differences. */
    def isSubseq(outer: Highlight.Position): Boolean = {
      val inner = this
      outer.elements.tails.exists { tail => tail.size >= inner.elements.size &&
        tail.zip(inner.elements).forall { case (o, i) => // outer/inner elements
          o.path == i.path &&
          o.index <= i.index && // outer must start before inner
          o.index + o.text.length >= i.index + i.text.length // outer must stop after inner
        }}
    }
  }

  /** Text that occurs before and after the highlighted text, along with the highlighted `text` itself. */
  case class Preview(lead: String, text: String, tail: String) {
    override def toString: String = s"${getClass.getSimpleName}(lead='$lead', text='$text', tail='$tail')"
  }

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
  implicit val hlJFmt: OFormat[Highlight] = Json.format[Highlight]
}
