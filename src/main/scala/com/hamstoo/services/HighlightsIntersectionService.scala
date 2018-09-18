/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.HighlightDao
import com.hamstoo.models.{Highlight, PageCoord, User}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
  * Intermediate between highlights DAO and controllers. It's function is to check new highlights for
  * intersections with existing highlights on the same page and to join them if such intersections are detected.
  */
@Singleton
class HighlightsIntersectionService @Inject()(implicit db: HighlightDao, ec: ExecutionContext) {

  val logger: Logger = Logger(getClass)

  /** Checks for intersections with existing highlights and rejects insert, inserts, or updates existing. */
  def add(highlight: Highlight): Future[Highlight] = for {

    // get all existing highlights by markId
    origHls <- db.retrieve(User(highlight.usrId), highlight.markId)

    // merge same-element text of the new highlight (It's assumed that frontend sends xpaths sorted by their position
    // in the document. Ideally there should be a check leading to rejecting requests to add highlights with error
    // message for a frontend dev to be aware of invalid requests.
    //   [https://github.com/Hamstoo/hamstoo/issues/178#issuecomment-339381263])
    hl = highlight.copy(pos = highlight.pos.copy(elements = mergeSameElems(highlight.pos.elements)))

    // find a single overlapping/touching/joinable existing highlight
    mbOverlap = origHls.view.map { origHl =>

                  // check for edge intersections as well as inclusion
                  (origHl, isEdgeIntsc(origHl.pos, hl.pos), isSubset(origHl.pos, hl.pos))

                }.find { case (_, edge, subs) => edge.isDefined || subs.isDefined }

    _ = if (mbOverlap.isDefined) logger.debug(s"Found existing highlight ${mbOverlap.get} ... that overlaps with new highlight $hl")

    newHl <- {

      // just insert the new highlight if none intersect with it ...
      mbOverlap.fold(db.insert(hl).map(_ => hl)) {

        // ... otherwise delete the existing original and recurse
        case (origHl, isEdgeWithOrigFirst, isNewSubsetOfOrig) => for {

          _ <- db.delete(origHl.usrId, origHl.id)
          _ = logger.debug(s"Deleted original highlight ${origHl.id} (isEdgeWithOrigFirst=$isEdgeWithOrigFirst, isNewSubsetOfOrig=$isNewSubsetOfOrig)")

          newHl <- {

            // recurse with existing highlight, if the new one is a subset of it (recursive step should really just
            // lead to an (re)insert, if all previous inserts/intersections went okay, but recurse anyway, just in case)
            if (isNewSubsetOfOrig.contains(true)) add(origHl)

            // update existing highlight if it's a subset of the new one (hl)
            else if (isNewSubsetOfOrig.contains(false)) add(hl)

            // update a single existing highlight with a union of the two
            else {
              val u = if (isEdgeWithOrigFirst.contains(true)) origHl.union(hl) else hl.union(origHl)
              add(u.copy(id = origHl.id)) // maintain the same id as a sanity check
            }
          }
        } yield newHl
      }
    }
  } yield newHl // return produced, updated, or existing highlight

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

  /** Checks whether one position is a subset of another. */
  def isSubset(posA: Highlight.Position, posB: Highlight.Position): Option[Boolean] = {

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
    if (subsetAofB) Some(false) else if (subsetBofA) Some(true) else None
  }

  /**
    * Checks if two highlights overlap by checking XPath lists intersection.
    *
    * @return  0 if no overlap
    *          1 if    overlap with A before B
    *         -1 if    overlap with B before A
    */
  def isEdgeIntsc(posA: Highlight.Position, posB: Highlight.Position): Option[Boolean] = {

    // look for sequences of paths that are tails of one Position and start of another
    val cont: Boolean = posB.startsWith(posA).nonEmpty
    val prep: Boolean = posA.startsWith(posB).nonEmpty

    if (cont) Some(true) else if (prep) Some(false) else None
  }
}
