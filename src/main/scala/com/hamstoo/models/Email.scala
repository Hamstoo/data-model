package com.hamstoo.models

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}


/**
  *
  * When a user share a mark, an email is send to a recipient. After that, the recipient, can reply to that email.
  *
  * @param from
  * @param to
  * @param subject
  * @param content
  * @param ts
  */
case class Email (
                  from: String,
                  to: String,
                  subject: String,
                  content: String,
                  ts: TimeStamp = TIME_NOW
                 ) {
  import EmailFormatters._
  def toJson: JsObject = Json.toJson(this).asInstanceOf[JsObject]
}

object Email extends BSONHandlers {

  val fromEmail: String = nameOf[Email](_.from)
  val toEmail: String = nameOf[Email](_.to)
  val emailSubject: String = nameOf[Email](_.subject)
  val TIMESTAMP: String = UserStats.TIMESTAMP;  assert(TIMESTAMP == nameOf[Email](_.ts))

  implicit val emailHandler: BSONDocumentHandler[Email] = Macros.handler[Email]
}

object EmailFormatters {
  implicit val emailJson: OFormat[Email] = Json.format[Email]
}