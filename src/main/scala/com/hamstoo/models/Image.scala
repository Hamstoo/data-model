package com.hamstoo.models

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ExtendedBytes, ExtendedString, ObjectId, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable

/**
  * See UrlDuplicate ScalaDoc.
  *
  * @param _id     This can be a UUID (as a String) for saving user pics or an ObjectId for saving mark images.
  * @param pic     Binary representation of the image to be saved.
  * @param markId  Optional mark ID to be associated with the image.  Marks can have multiple images.
  */
case class Image(pic: mutable.WrappedArray[Byte],
                 markId: Option[ObjectId] = None,
                 var picPrfx: Option[mutable.WrappedArray[Byte]] = None,
                 var markIdPrfx: Option[String] = None,
                 _id: String = generateDbId(Mark.ID_LENGTH)) {

  markIdPrfx = markId.map(_.binPrfxComplement)
  picPrfx = Some(pic.toArray.binaryPrefix)
}

object Image extends BSONHandlers {

  val ID: String = nameOf[Image](_._id)
  val PIC: String = nameOf[Image](_.pic)
  val MARK_ID: String = com.hamstoo.models.Page.MARK_ID;  assert(nameOf[Image](_.markId) == MARK_ID)
  val PICPRFX: String = nameOf[Image](_.picPrfx)
  val MPRFX: String = nameOf[Image](_.markIdPrfx)

  implicit val imageBsonHandler: BSONDocumentHandler[Image] = Macros.handler[Image]
}