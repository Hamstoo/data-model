package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

case class Recommendation (
  userId: UUID,
  source: String,
  params: Map[String, String],
  url: String,
  ts: TimeStamp = TIME_NOW
)

object Recommendation extends BSONHandlers {
  implicit val RecommandeyionJson: BSONDocumentHandler[Recommendation] = Macros.handler[Recommendation]
}