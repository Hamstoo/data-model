/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * _Fractional/relative_ coordinates of a node in a web page--used for annotation sorting.  These coordinates
  * are both fractional, i.e. relative to the full height and width of the page, so should range between 0 and 1.
  *
  * While `pageCoord` (global page <x,y> coordinates) are passed from the extension to the backend for saving to
  * the database for each annotation, they are only then used for sorting annotations before returning them in
  * sorted order to the frontend (full-page view and share email). They aren't ever returned to or used by the
  * Chrome extension.
  *
  * @param x  Relative horizontal position of annotation.
  * @param y  Relative vertical position of annotation.
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
  implicit val jFormat: OFormat[PageCoord] = Json.format[PageCoord]

  final val ZERO_COORD = PageCoord(0.0, 0.0)
}
