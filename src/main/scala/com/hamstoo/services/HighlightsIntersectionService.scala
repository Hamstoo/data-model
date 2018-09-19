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

        lazy val origFirstIntersection = hl.startsWith(origHl)
        lazy val newFirstIntersection = origHl.startsWith(hl)

        // if the new one is a subset of an existing highlight, then there's nothing to do (although we could
        // delete and re-insert it to confirm that all previous inserts/intersections went okay)
        if (hl.isSubseq(origHl)) Some { () =>
          Future.successful(origHl)

        // update existing highlight if it's a subset of the new one (hl), and use origHl.id which the
        // chrome-extension depends on
        } else if (origHl.isSubseq(hl)) Some { () =>
          db.delete(origHl.usrId, origHl.id).flatMap(delAt => add(hl.copy(id = origHl.id, timeFrom = delAt)))

        // if neither is a complete subset of the other, update existing highlight with a union of the two
        } else if (origFirstIntersection.nonEmpty) Some {
          () => {
            val u = origHl.union(hl, mbIntersection = Some(origFirstIntersection))
            db.delete(origHl.usrId, origHl.id).flatMap(delAt => add(u.copy(id = origHl.id, timeFrom = delAt)))
          }

        } else if (newFirstIntersection.nonEmpty) Some {
          () => {
            val u = hl.union(origHl, mbIntersection = Some(newFirstIntersection))
            db.delete(origHl.usrId, origHl.id).flatMap(delAt => add(u.copy(id = origHl.id, timeFrom = delAt)))
          }

        // just insert the new highlight if none intersect with it
        } else None

      }.find(_.isDefined).flatten // flatten the double Options

    _ = if (mbOverlap.isDefined) logger.debug(s"Found existing highlight that overlaps with new highlight: $hl")

    // just insert the new highlight if none intersect with it
    newHl <- mbOverlap.fold(db.insert(hl).map(_ => hl))(_())

    _ = if (mbOverlap.isDefined) logger.debug(s"New (possibly merged or subsumed) highlight: $newHl")

  } yield newHl // return produced, updated, or existing highlight
}
