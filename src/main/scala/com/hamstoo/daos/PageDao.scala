/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.nio.file.Files
import java.util.UUID

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global

/**
  * Website page content MongoDB data access object.  These used to be stored on the Marks themselves and
  * then moved over to the Reprs upon Representation computation, but now we just have them in their own
  * collection with references pointing in every which direction.
  */
@Singleton
class PageDao @Inject()(implicit db: () => Future[DefaultDB],
                        marksDao: MarkDao) {

  import com.hamstoo.utils._
  import com.hamstoo.models.Page._
  val logger: Logger = Logger(classOf[PageDao])

  private val dbColl: () => Future[BSONCollection] = () => db().map(_.collection("pages"))

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(MARK_ID -> Ascending :: Nil) % s"bin-$MARK_ID-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 403 seconds)

  /** Insert page to collection. */
  def insertPage(page: Page): Future[Page] = for {
    c <- dbColl()
    _ = logger.debug(s"Inserting page for mark ${page.markId}")
    wr <- c.insert(page)
    _ <- wr.failIfError
  } yield {
    logger.debug("Page inserted")
    page
  }

  /** Update a Page's reprId and add a ReprInfo to the Page's respective Mark. */
  def updateRepr(page: Page, reprId: ObjectId, reprInfoCreationTime: TimeStamp = TIME_NOW): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Updating page ${page.id} with repr ID '$reprId'")
    wr <- c.update(d :~ ID -> page.id, d :~ "$set" -> (d :~ REPR_ID -> reprId))
    _ <- wr.failIfError
    _ <- marksDao.insertReprInfo(page.markId, ReprInfo(reprId, page.reprType, created = reprInfoCreationTime))
  } yield logger.debug("Page was inserted")

   /** Process the file into a Page instance and add it to the Mark in the database. */
  def insertFilePage(userId: UUID, markId: String, file: TemporaryFile): Future[Page] =
    insertPage(Page(markId, ReprType.PRIVATE, Files.readAllBytes(file)))

  /**
    * Merge newMarkId's private pages into those of the existing existingMarkId.  Public and user-content
    * pages need not be merged; instead, they can be calculated again for the merged mark.
    */
  def mergePrivatePages(existingMarkId: String, userId: UUID, newMarkId: String): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Merging private pages for user $userId and marks: $existingMarkId and $newMarkId")
    // this next line should be consistent w/ the behavior of Mark.merge (i.e. only merge private reprs)
    sel = d :~ MARK_ID -> newMarkId :~ REPR_TYPE -> ReprType.PRIVATE.toString
    mod = d :~ "$set" -> (d :~ MARK_ID -> existingMarkId)
    wr <- c.update(sel, mod, multi = true)
    _ <- wr.failIfError
  } yield logger.debug(s"${wr.nModified} pages were merged")

  /**
    * Retrieves a mark's pages of a certain type.  Primarily used by the repr-engine when it receives a message
    * to process a private page.
    * @param markId          The mark ID of the pages to return.
    * @param reprType        The type (PUBLIC, PRIVATE, or USER_CONTENT) of pages to return.
    * @param bMissingReprId  If set to true, then only pages with missing reprIds will be returned, which is important
    *                        so that repr-engine doesn't try to re-process pages that already have their reprIds.
    */
  def retrievePages(markId: ObjectId, reprType: ReprType.Value, bMissingReprId: Boolean = true):
                                                                                    Future[Seq[Page]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving $reprType representations for mark $markId")
    sel = d :~ MARK_ID -> markId :~ REPR_TYPE -> reprType.toString :~
               (if (bMissingReprId) d :~ REPR_ID -> (d :~ "$exists" -> false) else d)
    seq <- c.find(sel).coll[Page, Seq]()
  } yield {
    logger.debug(s"${seq.size} $reprType pages were retrieved for mark $markId")
    seq
  }

  /** General method to handle deletion of user-content Pages given a mark ID. */
  def removeUserContentPage(markId: ObjectId): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Removing user-content page for mark $markId")
    wr <- c.remove(d :~ MARK_ID -> markId :~ REPR_TYPE -> ReprType.USER_CONTENT.toString)
    _ <- wr.failIfError
  } yield logger.debug(s"User-content page of mark $markId was deleted")
}
