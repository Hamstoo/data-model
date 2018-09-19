/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.HighlightDao
import com.hamstoo.models.{Highlight, User}
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
    hl = highlight.copy(pos = highlight.pos.mergeSameElems())

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

          delTime <- db.delete(origHl.usrId, origHl.id)
          _ = logger.debug(s"Deleted original highlight ${origHl.id} (isEdgeWithOrigFirst=$isEdgeWithOrigFirst, isNewSubsetOfOrig=$isNewSubsetOfOrig)")

          newHl <- {

            // recurse with existing highlight, if the new one is a subset of it (recursive step should really just
            // lead to an (re)insert, if all previous inserts/intersections went okay, but recurse anyway, just in case)
            if (isNewSubsetOfOrig.contains(true)) add(origHl.copy(timeFrom = delTime))

            // update existing highlight if it's a subset of the new one (hl), and use origHl.id which the
            // chrome-extension depends on
            else if (isNewSubsetOfOrig.contains(false)) add(hl.copy(id = origHl.id, timeFrom = delTime))

            // if neither is a complete subset of the other, update existing highlight with a union of the two
            else {
              val u = if (isEdgeWithOrigFirst.contains(true)) origHl.union(hl) else hl.union(origHl)
              add(u.copy(id = origHl.id, timeFrom = delTime)) // maintain the same id as a sanity check
            }
          }
        } yield newHl
      }
    }
  } yield newHl // return produced, updated, or existing highlight

  /** Checks whether one position is a subset of another. */
  def isSubset(posA: Highlight.Position, posB: Highlight.Position): Option[Boolean] = {

    // test if sets of paths are subsets and whether edge elements completely overlap
    if (posA.isSubseq(posB)) Some(false) else if (posB.isSubseq(posA)) Some(true) else None
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
    if (posB.startsWith(posA).nonEmpty) Some(true) else if (posA.startsWith(posB).nonEmpty) Some(false) else None
  }
}
