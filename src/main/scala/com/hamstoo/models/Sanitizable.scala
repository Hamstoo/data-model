package com.hamstoo.models

import play.api.libs.json._

import scala.annotation.implicitNotFound

sealed trait Sanitized[A] {
  implicit def protector: Protectable[A] // emulation of context bound
}
@implicitNotFound(
  "No sanitize Json deserializer found for type ${A}. Try to implement an implicit SanitizeReads or SanitizeFormat for this type."
)
trait SanitizedReads[A] extends Sanitized[A] with Reads[A]

@implicitNotFound(
  "No sanitize Json serializer found for type ${A}. Try to implement an implicit SanitizeWrites or SanitizeFormat for this type."
)
trait SanitizedWrites[A] extends Sanitized[A] with OWrites[A]

@implicitNotFound(
  "No sanitize Json serializer/deserializer found for type ${A}. Try to implement an implicit SanitizeFormat for this type."
)
trait SanitizedFormat[A] extends SanitizedWrites[A] with SanitizedReads[A]

/** Factory that create safe extensions of Play Reads/Writes/Format,
  * Required for safe serializing and deserializing instances on controllers.
  * */
object SanitizedJson {

  def reads[A](implicit rd: Reads[A], pr: Protectable[A]): SanitizedReads[A] = new SanitizedReads[A] {
    override implicit def protector: Protectable[A] = pr
    override def reads(json: JsValue): JsResult[A] = rd.reads(json).map(protector.protect)
  }

  def writes[A](implicit wr: OWrites[A], pr: Protectable[A]): SanitizedWrites[A] = new SanitizedWrites[A] {
    override implicit def protector: Protectable[A] = pr
    override def writes(o: A): JsObject = wr.writes(protector.protect(o))
  }

  def format[A](implicit fmt: OFormat[A], pr: Protectable[A]): SanitizedFormat[A] = new SanitizedFormat[A] {
    override implicit def protector: Protectable[A] = pr
    override def reads(json: JsValue): JsResult[A] = fmt.reads(json).map(pr.protect)
    override def writes(o: A): JsObject = fmt.writes(pr.protect(o))
  }
}

