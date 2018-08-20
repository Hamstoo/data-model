package com.hamstoo.models

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.services.TikaInstance
import com.hamstoo.utils.{ExtendedBytes, ExtendedString, ObjectId, generateDbId}
import play.api.Logger
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Try

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
                 var width: Option[Int] = None,
                 var height: Option[Int] = None,
                 var mimeType: Option[String] = None,
                 _id: String = generateDbId(Mark.ID_LENGTH)) {

  import Image.logger

  markIdPrfx = markId.map(_.binPrfxComplement)
  picPrfx = Some(pic.toArray.binaryPrefix)

  if (width.isEmpty || height.isEmpty) Try {
    val img = ImageIO.read(new ByteArrayInputStream(pic.toArray))
    width = Some(img.getWidth)
    height = Some(img.getHeight)
    logger.info(s"Detected width/height ${width.get}x${height.get} for image ${_id}")
  }

  if (mimeType.isEmpty) Try {
    mimeType = Some(TikaInstance.detect(pic.toArray))
    logger.info(s"Detected MIME type ${mimeType.get} for image ${_id}")
  }
}

object Image extends BSONHandlers {

  val logger = Logger(getClass)

  val ID: String = nameOf[Image](_._id)
  val PIC: String = nameOf[Image](_.pic)
  val MARK_ID: String = com.hamstoo.models.Page.MARK_ID;  assert(nameOf[Image](_.markId) == MARK_ID)
  val PICPRFX: String = nameOf[Image](_.picPrfx)
  val MPRFX: String = nameOf[Image](_.markIdPrfx)
  val WIDTH: String = nameOf[Image](_.width)
  val HEIGHT: String = nameOf[Image](_.height)
  val MIME_TYPE: String = nameOf[Image](_.mimeType); assert(MIME_TYPE == nameOf[Page](_.mimeType))

  implicit val imageBsonHandler: BSONDocumentHandler[Image] = Macros.handler[Image]
}