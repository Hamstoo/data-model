package com.hamstoo.models

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}


/**
  *
  * When a user share a mark, an email is send to a recipient. After that, the recipient, can reply to that email.
  *
  * @param interlocutor
  * @param addressee
  * @param topic
  * @param content
  * @param ts
  */
case class Discussion(
                       interlocutor: String,
                       addressee: String,
                       topic: String,
                       content: String,
                       ts: TimeStamp = TIME_NOW
                 ) {
  import DiscussionFormatters._
  def toJson: JsObject = Json.toJson(this).asInstanceOf[JsObject]
}

object Discussion extends BSONHandlers {

  val from: String = nameOf[Discussion](_.interlocutor)
  val to: String = nameOf[Discussion](_.addressee)
  val subject: String = nameOf[Discussion](_.topic)
  val TIMESTAMP: String = UserStats.TIMESTAMP;  assert(TIMESTAMP == nameOf[Discussion](_.ts))

  implicit val emailHandler: BSONDocumentHandler[Discussion] = Macros.handler[Discussion]
}

object DiscussionFormatters {
  implicit val discussionJson: OFormat[Discussion] = Json.format[Discussion]
}