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
}
