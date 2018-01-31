package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.services.TikaInstance
import com.hamstoo.utils.{ObjectId, TIME_NOW, TimeStamp, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable


/**
  * This is the data structure used to store external content, e.g. HTML files or PDFs.  It could be private content
  * downloaded via the Chrome extension, the repr-engine downloads public content given a URL, or the file upload
  * process uploads content directly from the user's computer.
  */
case class Page(userId: UUID,
                markId: ObjectId,
                reprType: String,
                mimeType: String,
                content: mutable.WrappedArray[Byte],
                created: TimeStamp = TIME_NOW,
                u_id: ObjectId = generateDbId(Mark.ID_LENGTH))

object Page extends BSONHandlers {

  /** A separate `apply` method that detects the MIME type automatically with Tika. */
  def apply(userId: UUID, markId: ObjectId, reprType: ReprType.Value, content: mutable.WrappedArray[Byte]): Page = {
    val mimeType = TikaInstance.detect(content.toArray[Byte])
    Page(userId, markId, reprType.toString, mimeType, content)
  }

  val U_ID: String = nameOf[Page](_.u_id)
  //val ID: String = com.hamstoo.models.Mark.ID;  assert(nameOf[Page](_.id) == ID)
  val USR: String = com.hamstoo.models.Mark.USR; assert(nameOf[Page](_.userId) == USR)
  val MARK_ID: String = nameOf[Page](_.markId)
  val REPR_TYPE: String = nameOf[Page](_.reprType)
  implicit val pageFmt: BSONDocumentHandler[Page] = Macros.handler[Page]
}