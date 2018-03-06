package com.hamstoo.models

import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Coordinates of a node in a web page.
  * @param x  Relative horizontal position of annotation--used for annotation sorting.
  * @param y  Relative vertical position of annotation--used for annotation sorting.
  */
case class PageCoord(x: Double, y: Double)

object PageCoord {

  /**
    * Function-predicate that sorts 2 PageCoords in decreasing order.
    * First sort by `y`, then if they are equal, trying to make comparision by `x`.
    */
  def sort(a: Option[PageCoord], b: Option[PageCoord]): Option[Boolean] = (a, b) match {
    case (Some(_), None) => Some(false)
    case (None, Some(_)) => Some(true)
    case (Some(a1), Some(b1)) if a1.y > b1.y => Some(false)
    case (Some(a1), Some(b1)) if a1.y == b1.y && a1.x > b1.x => Some(false)
    case (Some(a1), Some(b1)) if a1.y == b1.y && a1.x == b1.x =>
      None // indeterminate (previously missing, see chrome-extension issue #24)
    case (None, None) => None
    case _ => Some(true)
  }

  implicit val pageCoordHandler: BSONDocumentHandler[PageCoord] = Macros.handler[PageCoord]

  final val ZERO_COORD = PageCoord(0.0, 0.0)
}
