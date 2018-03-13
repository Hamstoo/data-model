package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.{ExtendedString, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable

/**
  * Keep track of which URLs have identical content to other URLs, per user.  For example, the following 2 URLs:
  *  https://www.nature.com/articles/d41586-017-07522-z?utm_campaign=Data%2BElixir&utm_medium=email&utm_source=Data_Elixir_160
  *  https://www.nature.com/articles/d41586-017-07522-z
  *
  * The two `var`s are used for database lookup and index.  Their respective non-`var`s are the true values.  `dups`
  * is the thing being looked up--a list of other URLs that are duplicated content of `url`.
  */
case class UrlDuplicate(userId: UUID,
                        url: String,
                        dups: Set[String],
                        var userIdPrfx: String = "", // why can't a simple string be used for urlPrfx also?
                        var urlPrfx: Option[mutable.WrappedArray[Byte]] = None,
                        id: String = generateDbId(Mark.ID_LENGTH)) {
  userIdPrfx = userId.toString.binPrfxComplement
  urlPrfx = Some(url.binaryPrefix)
}

object UrlDuplicate extends BSONHandlers {
  implicit val urldupBsonHandler: BSONDocumentHandler[UrlDuplicate] = Macros.handler[UrlDuplicate]

}
