package com.hamstoo.models

/**
  * Trait that define sort by page coordinates functionality
  */
trait Sortable {
  val pageCoord: Option[PageCoord]
}

object Sortable {

  /**
    * Function-predicate that sorts 2 PageCoords in decreasing order.
    * First sort by `y`, then if they are equal, trying to make comparision by `x`.
    */
  def sort(a: Sortable, b: Sortable): Boolean = (a.pageCoord, b.pageCoord) match {
    case (Some(_), None) => true
    case (Some(a1), Some(b1)) if a1.y > b1.y => true
    case (Some(a1), Some(b1)) if a1.y == b1.y && a1.x > b1.x => true
    case _ => false
  }
}
