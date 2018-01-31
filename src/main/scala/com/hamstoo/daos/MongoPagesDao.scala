package com.hamstoo.daos

import java.nio.file.Files
import java.util.UUID

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Website page content MongoDB data access object.  These used to be stored on the Marks themselves and
  * then moved over to the Reprs upon Representation computation, but now we just have them in their own
  * collection with references pointing in every which direction.
  */
class MongoPagesDao(db: () => Future[DefaultDB])(implicit ex: ExecutionContext) {

  import com.hamstoo.utils._
  import com.hamstoo.models.Page._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  private val dbColl: () => Future[BSONCollection] = () => db().map(_.collection("pages"))

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: MARK_ID -> Ascending :: Nil) % s"bin-$USR-1-$MARK_ID-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 203 seconds)

  /** Insert page to collection. */
  def insertPage(page: Page): Future[Page] = for {
    c <- dbColl()
    _ = logger.debug(s"Inserting page for user ${page.userId} and mark ${page.markId}")
    wr <- c.insert(page)
    _ <- wr.failIfError
  } yield {
    logger.debug("Page was inserted")
    page
  }

   /** Process the file into a Page instance and add it to the Mark in the database. */
  def insertFilePage(userId: UUID, id: String, file: TemporaryFile): Future[Page] =
    insertPage(Page(userId, id, ReprType.PRIVATE, Files.readAllBytes(file)))

  /** Retrieves all mark's pages. */
  def retrieveAllPages(userId: UUID, markId: ObjectId): Future[Seq[Page]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving representations for user $userId and mark $markId")
    sel = d :~ USR -> userId :~ MARK_ID -> markId
    seq <- c.find(sel).coll[Page, Seq]()
  } yield {
    logger.debug(s"${seq.size} pages were retrieved for user $userId and mark $markId")
    seq
  }

  /**
    * Merge newMarkId's private pages into those of the existing existingMarkId.  Public and user-content
    * pages need not be merged; instead, they can be calculated again for the merged mark.
    */
  def mergePrivatePages(existingMarkId: String, userId: UUID, newMarkId: String): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Merging private pages for user $userId and marks: $existingMarkId and $newMarkId")
    // this next line should be consistent w/ the behavior of Mark.merge (i.e. only merge private reprs)
    sel = d :~ USR -> userId :~ MARK_ID -> newMarkId :~ REPR_TYPE -> ReprType.PRIVATE.toString
    mod = d :~ "$set" -> (d :~ MARK_ID -> existingMarkId)
    wr <- c.update(sel, mod, multi = true)
    _ <- wr.failIfError
  } yield logger.debug(s"${wr.nModified} pages were merged")

  /** Retrieves a mark's pages of a certain type. */
  def retrievePages(userId: UUID, markId: ObjectId, reprType: ReprType.Value): Future[Seq[Page]] = for {
    c <- dbColl()
    _ = logger.debug(s"Retrieving $reprType representations for user $userId for mark $markId")
    sel = d :~ USR -> userId :~ MARK_ID -> markId :~ REPR_TYPE -> reprType.toString
    seq <- c.find(sel).coll[Page, Seq]()
  } yield {
    logger.debug(s"${seq.size} $reprType pages were retrieved for user $userId for mark $markId")
    seq
  }

  /** General method to handle deletion of Pages given a unique page ID. */
  def removePage(pageId: ObjectId): Future[Unit] = for {
    c <- dbColl()
    _ = logger.debug(s"Removing page $pageId")
    wr <- c.remove(d :~ ID -> pageId)
    _ <- wr.failIfError
  } yield logger.debug(s"Page $pageId was deleted")
}
