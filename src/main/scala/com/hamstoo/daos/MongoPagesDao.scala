package com.hamstoo.daos

import java.nio.file.Files
import java.util.UUID

import com.hamstoo.models.Mark.{ID, USR, pageFmt, _}
import com.hamstoo.models.{Mark, Page, Representation}
import play.api.Logger
import play.api.libs.Files.TemporaryFile
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson._

import scala.concurrent.{ExecutionContext, Future}

class MongoPagesDao(db: () => Future[DefaultDB])(implicit ex: ExecutionContext) {

  import com.hamstoo.utils._

  val logger: Logger = Logger(classOf[MongoMarksDao])

  val collName: String = "pages"

  private val dbColl: () => Future[BSONCollection] = () => db().map(_ collection collName)

  /** Insert page to collection */
  def insertPage(page: Page): Future[Page] = {
    logger.debug(s"Inserting page for user: ${page.userId} of mark: ${page.id}...")
    for {
      c <- dbColl()

      wr <- c.insert(page)

      _ <- wr failIfError
    } yield {
      logger.debug("Page was inserted.")
      page
    }
  }

  def bulkInsertPages(pages: Stream[Page]): Future[Int] = {
    logger.debug(s"Inserting stream of pages")

    for {
      c <- dbColl()

      pgs = pages.map(Mark.pageFmt.write)
      wr <- c.bulkInsert(pgs, ordered = false)
    } yield {
      val count = wr.totalN
      logger.debug(s"$count pages were successfully inserted")
      count
    }
  }

    /** Process the file into a Page instance and add it to the Mark in the database. */
  def insertFilePage(userId: UUID, id: String, file: TemporaryFile): Future[Page] = {
    val page = Page(userId, id, Representation.PRIVATE, Files.readAllBytes(file))
    insertPage(page)
  }

  /** Retrieves all mark's pages */
  def retrievePages(userId: UUID, id: String): Future[Seq[Page]] = {
    logger.debug(s"Retrieving representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id

      seq <- c.find(sel).coll[Page, Seq]()
    } yield {
      logger.debug(s"${seq.size} pages were retrieved fro user: $userId of mark: $id")
      seq
    }
  }

  /** Retrieves mark's private pages */
  def retrievePrivatePage(userId: UUID, id: String): Future[Seq[Page]] = {
    val reprType = Representation.PRIVATE

    logger.debug(s"Retrieving $reprType representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id :~ REPR_TYPE -> reprType

      seq <- c.find(sel).coll[Page, Seq]()
    } yield {
      logger.debug(s"${seq.size} $reprType pages were retrieved fro user: $userId of mark: $id")
      seq
    }
  }

  /** Retrieves mark's public page */
  def retrievePublicPage(userId: UUID, id: String): Future[Option[Page]] =
    retrievePages(userId, id, Representation.PUBLIC)

  /** Retrieves mark's users page */
  def retrieveUsersPage(userId: UUID, id: String): Future[Option[Page]] =
    retrievePages(userId, id, Representation.USERS)

//  /** Merging mark's pages together, the main action it's to deleting new*/
//  def mergePages(userId: UUID, oldId: String, newId: String) = {
//    for {
//
//      // retrieve only private representations
//      pages <- retrievePrivatePage(userId, newId)
//
//      // map pages to old one mark
//      pgs = pages.map(_.copy(id = oldId)).toStream
//
//      _ <- removePages(userId, newId)
//
//      _ <- bulkInsertPages(pgs)
//    }
//  }

  /**  Remove mark's all pages */
  def removePages(userId: UUID, id: String): Future[Unit] = {
    logger.debug(s"Removing page for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id
      wr <- c.remove(sel)
      _ <- wr failIfError
    } yield {
      logger.debug(s"Page for user: $userId and id: $id")
    }
  }

  /** General method to handle retrieving PUBLIC and USERS pages.*/
  private def retrievePages(userId: UUID, id: String, reprType: String): Future[Option[Page]] = {
    logger.debug(s"Retrieving $reprType representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id :~ REPR_TYPE -> reprType

      seq <- c.find(sel).one[Page]
    } yield {
      logger.debug(s"${seq.size} $reprType pages were retrieved fro user: $userId of mark: $id")
      seq
    }
  }
}
