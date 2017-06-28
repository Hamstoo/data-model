package com.hamstoo.models

import com.hamstoo.utils.fieldName
import com.hamstoo.models.Representation.Vec
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/** Vector as stored in mongo cache for conceptnet-vectors. */
case class VectorEntry(terms: Option[Set[String]], uri: Option[String], vector: Option[Vec])

object VectorEntry {
  val TERMS: String = fieldName[VectorEntry]("terms")
  val URI: String = fieldName[VectorEntry]("uri")
  val VEC: String = fieldName[VectorEntry]("vector")
  implicit val vecEntryHandler: BSONDocumentHandler[VectorEntry] = Macros.handler[VectorEntry]
}
