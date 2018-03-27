package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ObjectId, TimeStamp}

/**
  * An Annotation is user content that is created right on top of the web pages themselves (e.g. highlights
  * and inline notes) as opposed to complementary user content that merely corresponds to, or complements,
  * a web page (e.g. subject, tags, comments).
  *
  * Currently this trait defines base information with sort-by-page-coordinates functionality, which is used by the full-page
  * view in order to sort the annotations in the same order in which they appear on the page.
  */
trait Annotation extends Shareable with HasProtectedJsonPreview[Annotation] { // (backend implementation of Shareable *Annotations* doesn't exist yet)
  val usrId: UUID
  val id: ObjectId
  val markId: ObjectId
  val pos: Positions
  val pageCoord: Option[PageCoord]
  val memeId: Option[String]
  val timeFrom: TimeStamp
  val timeThru: TimeStamp

  /** We unfortunately used different variable names for this one in different model classes. */
  def userId: UUID = usrId
}

object Annotation {

  /**
    * Function-predicate that sorts 2 PageCoords in decreasing order.
    * First sort by `y`, then if they are equal, trying to make comparision by `x`.
    */
  def sort(a: Annotation, b: Annotation): Boolean =
    PageCoord.sort(a.pageCoord, b.pageCoord).getOrElse(Positions.sort(a.pos, b.pos))
}

trait AnnotationInfo extends BSONHandlers {
  val USR: String = nameOf[Annotation](_.usrId)
  val ID: String = nameOf[Annotation](_.id)
  val POS: String = nameOf[Annotation](_.pos)
  val PCOORD: String = nameOf[Annotation](_.pageCoord)
  val MARKID: String = nameOf[Annotation](_.markId)
  val MEM: String = nameOf[Annotation](_.memeId)
  val TIMEFROM: String = nameOf[Annotation](_.timeFrom)
  val TIMETHRU: String = nameOf[Annotation](_.timeThru)

  assert(nameOf[Annotation](_.timeFrom) == com.hamstoo.models.Mark.TIMEFROM)
  assert(nameOf[Annotation](_.timeThru) == com.hamstoo.models.Mark.TIMETHRU)
}
