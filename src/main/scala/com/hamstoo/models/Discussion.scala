/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ObjectId, TIME_NOW, TimeStamp}
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, BSONObjectID, Macros}

/**
  * When a user shares a mark, an email is send to a recipient.  After that, the recipient can reply to
  * that email and we will save its contents in a Discussion instance.
  *
  * @param markId     Mark to which this discussion belongs.
  * @param reDiscussionId  `_id` of the Discussion instance that this email is in reply to.  Or `None` for
  *                        *original* share emails, which aren't replies to anything.
  * @param sender     From whom the email was sent.  Can be either an email address or a UUID userId.  And yes,
  *                   this is an option because it's possible for a non-logged-in person to share a public mark.
  * @param recipient  To whom the email was sent.
  * @param subject    Email subject.
  * @param body       Email body.  Can be None, e.g. if original sharee does not include a "custom message."
  * @param ts         Email timestamp.
  * @param _id        "recommended solution is to provide an id yourself, using BSONObjectID.generate"
  *                     https://stackoverflow.com/questions/39353496/get-id-after-insert-with-reactivemongo
  */
case class Discussion(markId: ObjectId,
                      sender: Option[String] = None,
                      recipient: String,
                      subject: String,
                      body: Option[String] = None,
                      reDiscussionId: Option[BSONObjectID] = None,
                      ts: TimeStamp = TIME_NOW,
                      _id: BSONObjectID = BSONObjectID.generate) {
  import DiscussionFormatters._
  def toJson: JsObject = Json.toJson(this).asInstanceOf[JsObject]
}

object Discussion extends BSONHandlers {

  val SENDR: String = nameOf[Discussion](_.sender)
  val RECIP: String = nameOf[Discussion](_.recipient)
  val MARKID: ObjectId = nameOf[Discussion](_.markId)
  val TIMESTAMP: String = UserStats.TIMESTAMP;  assert(TIMESTAMP == nameOf[Discussion](_.ts))

  implicit val discussionHandler: BSONDocumentHandler[Discussion] = Macros.handler[Discussion]
}

object DiscussionFormatters {
  implicit val bsonObjIdJFmt: OFormat[BSONObjectID] = Json.format[BSONObjectID]
  implicit val discussionJFmt: OFormat[Discussion] = Json.format[Discussion]
}