package com.hamstoo.services

import com.hamstoo.daos.MongoHighlightDao
import com.hamstoo.models.{Highlight, PageCoord, User}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
  * Intermediate between highlights DAO and controllers. It's function is to check new highlights for
  * intersections with existing highlights on the same page and to join them if such intersections are detected.
  */
class HighlightsIntersectionService(hlightsDao: MongoHighlightDao)(implicit ec: ExecutionContext) {

  val logger: Logger = Logger(classOf[HighlightsIntersectionService])

  /** Checks for intersections with existing highlights and rejects insert, inserts, or updates existing. */
  def add(highlight: Highlight): Future[Highlight] = for {

    // get all highlights by markId
    hls <- hlightsDao.retrieve(User(highlight.usrId), highlight.markId)

    // merge same-element text of the new highlight (It's assumed that frontend sends xpaths sorted by their position
    // in the document. Ideally there should be a check leading to rejecting requests to add highlights with error
    // message for a frontend dev to be aware of invalid requests.
    //   [https://github.com/Hamstoo/hamstoo/issues/178#issuecomment-339381263])
    hl = highlight.copy(pos = highlight.pos.copy(elements = mergeSameElems(highlight.pos.elements)))

    // collect overlapping/touching/joinable existing highlights
    filtered = for {
      origHl <- hls // for each existing highlight in the DB
      edge = isEdgeIntsc(origHl.pos, hl.pos) // check for edge intersections
      subs = isSubset(origHl.pos, hl.pos) // check for inclusion
      if edge != 0 || subs != 0 // filter highlights for joining (i.e. ignore non-joining)
    } yield origHl -> (edge -> subs) // add comparison results to filtered highlights

    h <- filtered match {
      // just insert the new highlight if none intersect with it
      case Nil => hlightsDao insert hl map (_ => hl)

      // return original highlight if the new one is a subset of it
      case (origHl, (_, 1)) :: Nil => Future successful origHl

      // update existing (origHl) if it's a subset of the new one (hl)
      case (origHl, (_, -1)) :: Nil => hlightsDao.updateSoft(origHl.usrId, origHl.id, hl.pos, hl.preview, hl.pageCoord)

      // update existing with a union of the two
      case (origHl, (e, _)) :: Nil =>
        val (pos, prv, coord) = if (e > 0) union(origHl, hl) else union(hl, origHl)
        hlightsDao.updateSoft(origHl.usrId, origHl.id, pos, prv, coord)

      // fold all intersecting highlights while removing existing entries and insert a new aggregate highlight
      case seq =>
        val h = (hl /: seq) { case (nHl, (oHl, (_, s))) =>
          hlightsDao delete(oHl.usrId, oHl.id)
          if (s < 0) nHl else if (s > 0) nHl.copy(pos = oHl.pos, preview = oHl.preview)
          else {
            val (pos, prv, coord) = if (s > 0) union(oHl, nHl) else union(nHl, oHl)
            nHl.copy(pos = pos, preview = prv, pageCoord = coord)
          }
        }
        hlightsDao insert h map (_ => h)
    }
  } yield h // return produced, updated, or existing highlight

  /** Recursively joins same-XPath-elements to ensure there are no consecutive elements with the same XPath. */
  def mergeSameElems(
      es: Seq[Highlight.PositionElement],
      acc: Seq[Highlight.PositionElement] = Nil): Seq[Highlight.PositionElement] = {
    if (es.size < 2) (es ++ acc).reverse else {
      val t = es.tail
      if (es.head.path == t.head.path) mergeSameElems(es.head.copy(text = es.head.text + t.head.text) +: t.tail, acc)
      else mergeSameElems(t, es.head +: acc)
    }
  }

  implicit class ExtendedPosition0(private val second: Highlight.Position) /*extends AnyVal*/ {

    /** Returns a new Highlight.Position consisting of elements of `first` that overlap w/ start of `second`. */
    def startsWith(first: Highlight.Position) = Highlight.Position {
      first.elements.tails.find { _.zip(second.elements).forall { case (f, s) => // first/second elements
        f.path == s.path &&
        f.index <= s.index && // first must start before second
        f.index + f.text.length >= s.index && // first must stop after or at where second starts (a.k.a. overlap)
        f.index + f.text.length <= s.index + s.text.length // first must stop before second (o/w second would be subseq)
      }}.get // rely on the empty tail always forall'ing to true if a non-empty tail does not
    }
  }

  /**
    * Merges two positioning sequences and previews of two intersecting highlights.  At this point we know that
    * hlA appears before, and overlaps with, hlB.  This is different from how A and B are used in the other methods
    * of this class.
    */
  def union(hlA: Highlight, hlB: Highlight): (Highlight.Position, Highlight.Preview, Option[PageCoord]) = {

    // look for longest paths sequence that is a tail of highlight A and a start of highlight B
    val intersection = hlB.pos.startsWith(hlA.pos).elements

    val tailB = hlB.pos.elements.drop(intersection.size - 1)

    // eA and eB are the same XPath node, so join them
    val eA = hlA.pos.elements.last
    val eB = tailB.head
    val joinedText = eA.text + eB.text.substring(eA.index + eA.text.length - eB.index, eB.text.length)
    val joinedElem: Highlight.PositionElement = eA.copy(text = joinedText) // use eA's `index`

    // drop last element of highlight A (which could include only part of that element's text while highlight B is
    // guaranteed to include more) and first n - 1 intersecting elements of highlight B
    val elsUnion = hlA.pos.elements.init ++ Seq(joinedElem) ++ tailB.tail

    val prvUnion = Highlight.Preview(hlA.preview.lead, ("" /: elsUnion) (_ + _.text), hlB.preview.tail)
    (Highlight.Position(elsUnion), prvUnion, hlA.pageCoord.orElse(hlB.pageCoord))
  }

  /** Checks whether one position is a subset of another. */
  def isSubset(posA: Highlight.Position, posB: Highlight.Position): Int = {

    implicit class ExtendedPosition1(private val inner: Highlight.Position) /*extends AnyVal*/ {

      /** Very similar to ExtendedPosition0.startsWith, but some important minor differences. */
      def isSubseq(outer: Highlight.Position): Boolean = {
        outer.elements.tails.exists { tail => tail.size >= inner.elements.size &&
          tail.zip(inner.elements).forall { case (o, i) => // outer/inner elements
            o.path == i.path &&
            o.index <= i.index && // outer must start before inner
            o.index + o.text.length >= i.index + i.text.length // outer must stop after inner
        }}
      }
    }

    // test if sets of paths are subsets and whether edge elements completely overlap
    val subsetAofB: Boolean = posA.isSubseq(posB)
    val subsetBofA: Boolean = posB.isSubseq(posA)
    if (subsetAofB) -1 else if (subsetBofA) 1 else 0
  }

  /**
    * Checks if two highlights overlap by checking XPath lists intersection.
    *
    * @return  0 if no overlap
    *          1 if    overlap with A before B
    *         -1 if    overlap with B before A
    */
  def isEdgeIntsc(posA: Highlight.Position, posB: Highlight.Position): Int = {

    // look for sequences of paths that are tails of one Position and start of another
    val cont: Boolean = posB.startsWith(posA).nonEmpty
    val prep: Boolean = posA.startsWith(posB).nonEmpty

    if (cont) 1 else if (prep) -1 else 0
  }
}
