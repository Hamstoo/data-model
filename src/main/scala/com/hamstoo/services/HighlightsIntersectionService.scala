package com.hamstoo.services

import com.hamstoo.daos.MongoHighlightDao
import com.hamstoo.models.{Highlight, PageCoord}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
  * Intermediate between highlights DAO and controllers. It's function is to check new highlights for
  * intersections with existing highlights on the same page and to join them if such intersections are detected.
  */
class HighlightsIntersectionService (hlightsDao: MongoHighlightDao)(implicit ec: ExecutionContext) {

  val logger: Logger = Logger(classOf[HighlightsIntersectionService])

  /** Checks for intersections with existing highlights and rejects insert, inserts, or updates existing. */
  def add(highlight: Highlight): Future[Highlight] = for {

    // get all highlights by markId
    hls <- hlightsDao.retrieveByMarkId(highlight.usrId, highlight.markId)

    // merge same-element text of the new highlight (is `sortBy(_.path)` required here?)
    hl = highlight.copy(pos = highlight.pos.copy(elements = mergeSameElems(highlight.pos.elements/*.sortBy(_.path)*/)))

    // collect overlapping/touching/joinable existing highlights
    filtered = for {
      origHl <- hls // for each existing highlight in the DB
      edge = isEdgeIntsc(origHl.pos, hl.pos) // check for edge intersections
      subs = isSubset(origHl.pos, hl.pos) // check for inclusion
      if edge.isDefined || subs.isDefined // filter highlights for joining (i.e. ignore non-joining)
    } yield origHl -> (edge -> subs) // add comparison results to filtered highlights

    h <- filtered match {
      // just insert the new highlight if none intersect with it
      case Nil => hlightsDao insert hl map (_ => hl)

      // return original highlight if the new one is a subset of it
      case (origHl, (_, Some(false))) :: Nil => Future successful origHl

      // update existing (origHl) if it's a subset of the new one (hl)
      case (origHl, (_, Some(true))) :: Nil =>
        hlightsDao update(origHl.usrId, origHl.id, hl.pos, hl.preview, hl.pageCoord)

      // update existing with a union of the two
      case (origHl, (e, _)) :: Nil =>
        val (pos, prv, coord) = if (e.contains(true)) union(origHl, hl) else union(hl, origHl)
        hlightsDao update(origHl.usrId, origHl.id, pos, prv, coord)

      // fold all intersecting highlights while removing existing entries and insert a new aggregate highlight
      case seq =>
        val h = (hl /: seq) { case (nHl, (oHl, (e, s))) =>
          hlightsDao delete(oHl.usrId, oHl.id)
          if (s.contains(true)) nHl else if (s.contains(false)) nHl.copy(pos = oHl.pos, preview = oHl.preview)
          else {
            val (pos, prv, coord) = if (e.contains(true)) union(oHl, nHl) else union(nHl, oHl)
            nHl.copy(pos = pos, preview = prv, pageCoord = coord)
          }
        }
        hlightsDao insert h map (_ => h)
    }
  } yield h // return produced, updated, or existing highlight

  /** Recursively joins same-element text pieces of a highlight. */
  def mergeSameElems(es: Seq[Highlight.PositionElement],
                     acc: Seq[Highlight.PositionElement] = Nil): Seq[Highlight.PositionElement] = {
    if (es.size < 2) return es ++ acc reverse
    val t = es.tail
    if (es.head.path == t.head.path) {
      if (es.head.text != t.head.text)
        logger.warn(s"How useful can this text be if we're simply concatentating it like this? (path = '${es.head.path}', e0 = '${es.head.text}', , e1 = '${es.head.text}')")
      mergeSameElems(t.tail, es.head.copy(text = es.head.text + t.head.text) +: acc)
    }
    else mergeSameElems(t, es.head +: acc)
  }

  /** Merges two positioning sequences and previews of two intersecting highlights. */
  def union(hlA: Highlight, hlB: Highlight): (Highlight.Position, Highlight.Preview, Option[PageCoord]) = {
    val posA: Highlight.Position = hlA.pos
    val posB: Highlight.Position = hlB.pos
    val elsA = posA.elements
    val elsB = posB.elements
    val pthsA: Seq[String] = elsA.map(_.path)
    val pthsB: Seq[String] = elsB.map(_.path)

    // look for longest paths sequence that is a tail of position A and a start of position B
    val intersection: Seq[String] = pthsA.tails find (pthsB startsWith _) get

    // if intersection is longer than single element, then drop last of heading highlight and first n - 1
    // intersecting elements of trailing highlight
    val elsUnion: Seq[Highlight.PositionElement] =
      if (intersection.size > 1)
        elsA.init ++ elsB.drop(intersection.size - 1)
      // otherwise merge by concatenating texts of the single intersecting element
      else
        elsA.init ++
          (Highlight.PositionElement(elsB.head.path, elsA.last.text.take(posB.initIndex) + elsB.head.text) +: elsB.tail)

    val prvUnion = Highlight.Preview(hlA.preview.lead, ("" /: elsUnion) (_ + _.text), hlB.preview.tail)
    (Highlight.Position(elsUnion, posA.initIndex), prvUnion, hlA.pageCoord.orElse(hlB.pageCoord))
  }

  /** Checks whether one position is a subset of another.  Returns true if A is a subset of B. */
  def isSubset(posA: Highlight.Position, posB: Highlight.Position): Option[Boolean] = {
    val esA: Seq[Highlight.PositionElement] = posA.elements
    val esB: Seq[Highlight.PositionElement] = posB.elements
    val psA: Seq[String] = esA.map(_.path)
    val psB: Seq[String] = esB.map(_.path)
    val setA: Set[String] = psA.toSet
    val setB: Set[String] = psB.toSet
    val headDif: Boolean = psA.head != psB.head
    val lastDif: Boolean = psA.last != psB.last
    lazy val frstIndxComp: Int = posA.initIndex compareTo posB.initIndex
    /* compare indexes of last characters in two highlights: */
    lazy val lastIndxComp: Int = esA.last.text.length + (if (esA.size > 1) 0 else posA.initIndex) compareTo
      esB.last.text.length + (if (esB.size > 1) 0 else posB.initIndex)
    /* test if sets of paths are subsets and whether edge elements completely overlap: */
    val subsetAofB: Boolean =
      (setA subsetOf setB) && (headDif || frstIndxComp >= 0) && (lastDif || lastIndxComp <= 0)
    lazy val subsetBofA: Boolean =
      (setB subsetOf setA) && (headDif || frstIndxComp <= 0) && (lastDif || lastIndxComp >= 0)
    if (subsetAofB) Some(true) else if (subsetBofA) Some(false) else None
  }

  /** Checks if two highlights overlap by checking xpath lists intersection for text overlaps. */
  def isEdgeIntsc(posA: Highlight.Position, posB: Highlight.Position): Option[Boolean] = {
    val elemsA: Seq[Highlight.PositionElement] = posA.elements
    val elemsB: Seq[Highlight.PositionElement] = posB.elements
    val pathsA: Seq[String] = elemsA.map(_.path)
    val pathsB: Seq[String] = elemsB.map(_.path)
    /* look for sequences of paths that are tails of one position and start of another: */
    lazy val fwd: Seq[String] = pathsA.tails find (pathsB startsWith _) get
    lazy val bck: Seq[String] = pathsB.tails find (pathsA startsWith _) get
    /* test if matching sequences aren't empty and if edge char indexes overlap in case of one element intersection: */
    lazy val cont: Boolean = fwd.size > 1 ||
      fwd.nonEmpty && elemsA.last.text.length + (if (elemsA.length > 1) 0 else posA.initIndex) >= posB.initIndex
    lazy val prep: Boolean = bck.size > 1 ||
      bck.nonEmpty && elemsB.last.text.length + (if (elemsB.length > 1) 0 else posB.initIndex) >= posA.initIndex
    if (cont) Some(true) else if (prep) Some(false) else None
  }
}
