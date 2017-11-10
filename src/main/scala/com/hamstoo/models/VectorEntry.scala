package com.hamstoo.models

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Representation.Vec
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/** Vector as stored in mongo cache for conceptnet-vectors. */
case class VectorEntry(uri: Option[String], vector: Option[Vec])

object VectorEntry {
  val URI: String = nameOf[VectorEntry](_.uri)
  val VEC: String = nameOf[VectorEntry](_.vector)
  implicit val vecEntryHandler: BSONDocumentHandler[VectorEntry] = Macros.handler[VectorEntry]
}
