package com.hamstoo.models

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Representation.Vec
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/** Vector as stored in mongo cache for conceptnet-vectors. */
case class VectorEntry(terms: Option[Set[String]], uri: Option[String], vector: Option[Vec])

object VectorEntry {
  val TERMS: String = nameOf[VectorEntry](_.terms)
  val URI: String = nameOf[VectorEntry](_.uri)
  val VEC: String = nameOf[VectorEntry](_.vector)
  implicit val vecEntryHandler: BSONDocumentHandler[VectorEntry] = Macros.handler[VectorEntry]
}
