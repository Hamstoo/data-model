package com.hamstoo.models

import play.api.libs.json._

import scala.annotation.implicitNotFound

@implicitNotFound(
  "No sanitize Json deserializer found for type ${A}. Try to implement an implicit SanitizeReads or SanitizeFormat for this type."
)
trait SanitizedReads[A <: Protectable[A]] extends Reads[A]

@implicitNotFound(
  "No sanitize Json serializer found for type ${A}. Try to implement an implicit SanitizeWrites or SanitizeFormat for this type."
)
trait SanitizedWrites[A <: Protectable[A]] extends OWrites[A]

@implicitNotFound(
  "No sanitize Json serializer/deserializer found for type ${A}. Try to implement an implicit SanitizeFormat for this type."
)
trait SanitizedFormat[A <: Protectable[A]] extends OFormat[A]

/** Object that create safe extensions of Play Reads/Writes/Format,
  * Required for safe serializing and deserializing instances on controllers.
  * */
object SanitizedJson {

  def reads[A <: Protectable[A]](implicit rd: Reads[A]): SanitizedReads[A] = (json: JsValue) => {
    rd.reads(json).map(_.protect)
  }

  def writes[A <: Protectable[A]](implicit wr: OWrites[A]): SanitizedWrites[A] = (o: A) => {
    wr.writes(o.protect)
  }

  def format[A <: Protectable[A]](implicit fmt: OFormat[A]): SanitizedFormat[A] = new SanitizedFormat[A] {
    override def reads(json: JsValue): JsResult[A] = {
      fmt.reads(json).map(_.protect)
    }
    override def writes(o: A): JsObject = {
      fmt.writes(o.protect)
    }
  }
}

