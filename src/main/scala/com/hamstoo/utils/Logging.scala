package com.hamstoo.utils

import com.hamstoo.daos.MongoInlineNoteDao
import play.api.Logger

trait Logging {
  val log: Logger = Logger(classOf[MongoInlineNoteDao])
}
