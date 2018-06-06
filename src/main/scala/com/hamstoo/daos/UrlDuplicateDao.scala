/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.Mark.{URLPRFX, USRPRFX, _}
import com.hamstoo.models.UrlDuplicate
import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, ExtendedString, ExtendedWriteResult, ObjectId, d}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * DAO to maintain a bi-directional mapping of URLs to lists of duplicates of those URLs.  These duplicates
  * are used when marking a page to determine if a page with the same content has already been marked, in which
  * case the marks will be merged.
  */
class UrlDuplicateDao @Inject()(implicit db: () => Future[DefaultDB]) {

  val logger: Logger = Logger(classOf[UrlDuplicateDao])

  private val dupsIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(USRPRFX -> Ascending :: URLPRFX -> Ascending :: Nil) % s"bin-$USRPRFX-1-$URLPRFX-1" ::
    Nil toMap

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "urldups")
  Await.result(dbColl().map(_.indexesManager.ensure(dupsIndxs)), 232 seconds)

  /**
    * Map each URL to the other in the `urldups` collection.  The only reason this method (currently) returns a
    * Future[String] rather than a Future[Unit] is because of where it's used in repr-engine.
    */
  def insertUrlDup(user: UUID, url0: String, url1: String): Future[String] = for {
    c <- dbColl()
    _ = logger.debug(s"Inserting URL duplicates for $url0 and $url1")

    // database lookup to find candidate dups via indexed prefixes
    optDups0 <- retrieve(user, url0).map(_.headOption)
    optDups1 <- retrieve(user, url1).map(_.headOption)

    // construct a new UrlDuplicate or an update to the existing one
    newUD = (urlKey: String, urlVal: String, optDups: Option[UrlDuplicate]) =>
      optDups.fold(UrlDuplicate(user, urlKey, Set(urlVal)))(ud => ud.copy(dups = ud.dups + urlVal))
    newUD0 = newUD(url0, url1, optDups0)
    newUD1 = newUD(url1, url0, optDups1)

    // update or insert if not already there
    _ <- update(newUD0.id, newUD0, upsert = true)
    _ <- update(newUD1.id, newUD1, upsert = true)
  } yield ""

  /** Inserting duplicate in database. */
  def insert(urlDup: UrlDuplicate): Future[UrlDuplicate] = for {
    c <- dbColl()
    _ = logger.debug(s"Inserting URL duplicate for user: ${urlDup.userId}")
    wr <- c.insert(urlDup)
    _ <- wr.failIfError
  } yield {
    logger.debug(s"Url duplicate inserted for ${urlDup.userId}")
    urlDup
  }

  /** Retrieve duplicates for user.  Same prefix-search-then-filter implementation as ImageDao.retrieve.*/
  def retrieve(userId: UUID, url: String): Future[Set[UrlDuplicate]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving URL duplicates for $userId, URL: $url")
    sel = d :~ USRPRFX -> userId.toString.binPrfxComplement :~ URLPRFX -> url.binaryPrefix
    candidates <- c.find(sel).coll[UrlDuplicate, Set]()
  } yield {
    // narrow down candidates sets to non-indexed (non-prefix) values (there should really only be 1, but
    // we use `filter` rather than `find` anyway so that data errors are not hidden)
    val dups = candidates.filter(ud => ud.userId == userId && ud.url == url)
    logger.debug(s"${dups.size} URL duplicates were retrieved")
    dups
  }

  /** Update a UrlDuplicate or insert if not found and `upsert` is true. */
  def update(id: ObjectId, urlDuplicate: UrlDuplicate, upsert: Boolean = false): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Updating url duplicate $id")
    wr <- c.update(d :~ ID -> id, urlDuplicate, upsert = upsert)
    _ <- wr.failIfError
  } yield {
    logger.debug(s"UrlDuplicate $id was updated")
  }
}
