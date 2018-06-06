/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.Inject
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


/**
  * Data access object for tracking (Facebook) events.
  */
class TrackingDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(getClass)

  private def trackingColl(): Future[BSONCollection] = db().map(_.collection("tracking"))

  // `tracking` collection fields
  private val SRC = "source"
  private val N_HITS = "nHits"
  private val FB_CONTENT_ID = "fbContentId"

  /**
    * Map a trackingSource code to a Facebook Content ID, which is used as a parameter passed to FB event tracking.
    *
    * This function assumes that a document/record/row has already been populated, probably manually, in the database
    * although that is not required.  In such cases the increment will still occur, so that we don't lose any data,
    * but the result will be None
    */
  def retrieveFbContentId(trackingSource: String): Future[Option[String]] = for {
    c <- trackingColl()
    wr <- c.findAndUpdate(d :~ SRC -> trackingSource, d :~ "$inc" -> (d :~ N_HITS -> 1), upsert = true)
  } yield wr.result[BSONDocument].flatMap(_.getAs[String](FB_CONTENT_ID))
}
