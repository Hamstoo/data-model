package com.hamstoo.models

import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 2017-09-10
  *
  * @param x  Relative horizontal position of annotation--used for annotation sorting.
  * @param y  Relative vertical position of annotation--used for annotation sorting.
  */
case class PageCoord(x: Double, y: Double)

object PageCoord {

  implicit val pageCoordHandler: BSONDocumentHandler[PageCoord] = Macros.handler[PageCoord]

  final val ZERO_COORD = PageCoord(0.0, 0.0)
}
