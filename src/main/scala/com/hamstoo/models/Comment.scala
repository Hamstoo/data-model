package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.models.Comment.{CommentPos}
import com.hamstoo.utils.ExtendedString
import org.joda.time.DateTime
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.mutable
import scala.util.Random

/**
  * Data model of a text highlight.
  *
  * @param usrId    owner UUID
  * @param id       highlight id, common for all versions
  * @param url      URL of the web page where highlighting was done
  * @param uPref    binary prefix of the URL for indexing; set by class init
  * @param pos      array of positioning data
  * @param memeId   'highlight representation' id, to be implemented
  * @param timeFrom timestamp
  * @param timeThru version validity time
  */
case class Comment(
                      usrId: UUID,
                      id: String = Random.alphanumeric take Comment.ID_LENGTH mkString,
                      url: String,
                      var uPref: Option[mutable.WrappedArray[Byte]] = None,
                      pos: Seq[CommentPos],
                      memeId: Option[String] = None,
                      timeFrom: Long = DateTime.now.getMillis,
                      timeThru: Long = Long.MaxValue) {
  uPref = Some(url.prefx)
}

object Comment extends BSONHandlers {

  case class CommentPos(path: String, text: String, indx: Int)


  val ID_LENGTH: Int = 16
  val USR: String = nameOf[Comment](_.usrId)
  val ID: String = nameOf[Comment](_.id)
  val POS: String = nameOf[Comment](_.pos)
  val PATH: String = nameOf[CommentPos](_.path)
  val TEXT: String = nameOf[CommentPos](_.text)
  val INDX: String = nameOf[CommentPos](_.indx)
  val URL: String = nameOf[Comment](_.url)
  val UPRF: String = nameOf[Comment](_.uPref)
  val MEM: String = nameOf[Comment](_.memeId)
  val TSTMP: String = nameOf[Comment](_.timeFrom)
  val TILL: String = nameOf[Comment](_.timeThru)
  implicit val hlposBsonHandler: BSONDocumentHandler[CommentPos] = Macros.handler[CommentPos]
  implicit val highlightHandler: BSONDocumentHandler[Comment] = Macros.handler[Comment]
}
