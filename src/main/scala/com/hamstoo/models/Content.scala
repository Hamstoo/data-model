package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf

trait Content {
  val usrId: UUID
  val id: String
  val markId: String
  val pos: Position
  val memeId: Option[String]
  val timeFrom: Long
  val timeThru: Long
}

trait ContentInfo extends BSONHandlers {
  val USR: String = nameOf[Content](_.usrId)
  val ID: String = nameOf[Content](_.id)
  val POS: String = nameOf[Content](_.pos)
  val MARKID: String = nameOf[Content](_.markId)
  val MEM: String = nameOf[Content](_.memeId)
  val TIMEFROM: String = nameOf[Content](_.timeFrom)
  val TIMETHRU: String = nameOf[Content](_.timeThru)

  assert(nameOf[Content](_.timeFrom) == com.hamstoo.models.Mark.TIMEFROM)
  assert(nameOf[Content](_.timeThru) == com.hamstoo.models.Mark.TIMETHRU)

}
