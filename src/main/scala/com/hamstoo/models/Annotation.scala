package com.hamstoo.models

/**
  * An Annotation is user content that is created right on top of the web pages themselves (e.g. highlights
  * and inline notes) as opposed to complementary user content that merely corresponds to, or complements,
  * a web page (e.g. subject, tags, comments).
  *
  * Currently this trait only defines sort-by-page-coordinates functionality, which is used by the full-page
  * view in order to sort the annotations in the same order in which they appear on the page.
  */
trait Annotation {
  val pageCoord: Option[PageCoord]
}

object Annotation {

  /**
    * Function-predicate that sorts 2 PageCoords in decreasing order.
    * First sort by `y`, then if they are equal, trying to make comparision by `x`.
    */
  def sort(a: Annotation, b: Annotation): Boolean = (a.pageCoord, b.pageCoord) match {
    case (Some(_), None) => true
    case (Some(a1), Some(b1)) if a1.y > b1.y => true
    case (Some(a1), Some(b1)) if a1.y == b1.y && a1.x > b1.x => true
    case _ => false
  }
}
