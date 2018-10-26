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
  * @param fromEmail  From whom the email was sent.
  * @param toEmail    To whom the email was sent.
  * @param subject    Email subject.
  * @param body       Email body.
  * @param ts         Email timestamp.
  * @param _id        "recommended solution is to provide an id yourself, using BSONObjectID.generate"
  *                     https://stackoverflow.com/questions/39353496/get-id-after-insert-with-reactivemongo
  */
case class Discussion(markId: ObjectId,
                      reDiscussionId: Option[BSONObjectID] = None,
                      fromEmail: String,
                      toEmail: String,
                      subject: String,
                      body: String,
                      ts: TimeStamp = TIME_NOW,
                      _id: BSONObjectID = BSONObjectID.generate) {
  import DiscussionFormatters._
  def toJson: JsObject = Json.toJson(this).asInstanceOf[JsObject]
}

object Discussion extends BSONHandlers {

  val FROM: String = nameOf[Discussion](_.fromEmail)
  val TO: String = nameOf[Discussion](_.toEmail)
  val SUBJECT: String = nameOf[Discussion](_.subject)
  val TIMESTAMP: String = UserStats.TIMESTAMP;  assert(TIMESTAMP == nameOf[Discussion](_.ts))

  implicit val discussionHandler: BSONDocumentHandler[Discussion] = Macros.handler[Discussion]
}

object DiscussionFormatters {
  implicit val bsonObjIdJFmt: OFormat[BSONObjectID] = Json.format[BSONObjectID]
  implicit val discussionJFmt: OFormat[Discussion] = Json.format[Discussion]
}