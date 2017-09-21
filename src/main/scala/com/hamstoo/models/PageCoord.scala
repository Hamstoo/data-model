package com.hamstoo.models

import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 2017-09-10
  */
case class PageCoord(x: Double, y: Double)

object PageCoord {
  implicit val pageCoordHandler: BSONDocumentHandler[PageCoord] = Macros.handler[PageCoord]

  final val ZERO_COORD = PageCoord(0.0, 0.0)

  /**
    * Function-predicate that sorts 2 PageCoords in decreasing order.
    * First sort by `y`, then if they are equal, trying to make comparision by `x`.
    */
  def sortWith(a: Option[PageCoord], b: Option[PageCoord]): Boolean = (a, b) match {
    case (Some(_), None) => true
    case (Some(a1), Some(b1)) if a1.y > b1.y => true
    case (Some(a1), Some(b1)) if a1.y == b1.y && a1.x > b1.x => true
    case _ => false
  }
}
