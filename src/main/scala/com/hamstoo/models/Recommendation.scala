/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * TODO 266
  *
  * @param userId
  * @param source  One of the values from RecSourceEnum (as a String).
  * @param params
  * @param url
  * @param ts
  */
case class Recommendation (
  userId: UUID,
  source: String,
  params: Map[String, String],
  url: String,
  ts: TimeStamp = TIME_NOW
)

object Recommendation extends BSONHandlers {

  val USR: String = Mark.USR; assert(USR == nameOf[Recommendation](_.userId))
  val TIMESTAMP: String = UserStats.TIMESTAMP; assert(TIMESTAMP == nameOf[Recommendation](_.userId))

  /**
    * Enumeration of various sources of recommendations.
    * See Representation.VecEnum for something similar.
    *
    * TODO 266: if we're comparing request URLs against these values in mediumPostsToRecommendation, then we may
    *           need to use Value("DuckDuckGo") but that should really be made more obvious via a defined function
    */
  object SrcEnum extends Enumeration {
    val DUCK_DUCK_GO,
        MEDIUM
      = Value
  }

  implicit val recommendationHandler: BSONDocumentHandler[Recommendation] = Macros.handler[Recommendation]
}