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

  /** Insert page to collection. */
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

  /** Insert multiple pages to collections. */
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

  /** Retrieves all mark's pages. */
  def retrieveAllPages(userId: UUID, id: String): Future[Seq[Page]] = {
    logger.debug(s"Retrieving representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id

      seq <- c.find(sel).coll[Page, Seq]()
    } yield {
      logger.debug(s"${seq.size} pages were retrieved for user: $userId of mark: $id")
      seq
    }
  }

  /** Merging mark's pages together, the main action it's to deleting new*/
  def mergePrivatePages(userId: UUID, oldId: String, newId: String): Future[Unit] = {
    logger.debug(s"Merging private pages for user: $userId marks: $oldId and $newId")

    for {
      // retrieve only private representations
      pages <- retrievePrivatePages(userId, newId)

      // map pages to old one mark
      pgs = pages.map(_.copy(id = oldId)).toStream

      // remove new one pages
      _ <- removePrivatePage(userId, newId)

      // reinsert pages with old mark id
      _ <- bulkInsertPages(pgs)
    } yield {
      logger.debug(s"Private pages was merged")
    }
  }

  /** Merge public marks pages */
  def mergePublicPages(userId: UUID, oldId: String, newId: String): Future[Unit] = {
    logger.debug(s"Merging public pages for user: $userId of marks: $oldId and $newId")

    for {
      // retrieve new one pub page
      optNewPg <- retrievePublicPage(userId, newId)

      // change marks id
      updPg = optNewPg.map(_.copy(id = oldId))

      // delete new one public marks
      _ <- removePublicPage(userId, newId)

      // update old one public repr by new, if exist
      _ <- updatePublicPage(userId, oldId, updPg)
    } yield {
      logger.debug(s"Public pages was merged for user: $userId of marks: $oldId, $newId")
    }
  }

  /** Merge user marks pages */
  def mergeUserPages(userId: UUID, oldId: String, newId: String): Future[Unit] = {
    logger.debug(s"Merging user pages for user: $userId of marks: $oldId and $newId")

    for {
      // retrieve new one pub page
      optNewPg <- retrieveUserPage(userId, newId)

      // change marks id
      updPg = optNewPg.map(_.copy(id = oldId))

      // delete new one public marks
      _ <- removeUserPage(userId, newId)

      // update old one public repr by new, if exist
      _ <- updateUserPage(userId, oldId, updPg)
    } yield {
      logger.debug(s"User pages was merged for user: $userId of marks: $oldId, $newId")
    }
  }

  /** Retrieves mark's private pages */
  def retrievePrivatePages(userId: UUID, id: String): Future[Seq[Page]] = {
    val reprType = Representation.PRIVATE

    logger.debug(s"Retrieving $reprType representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      sel = d :~ USR -> userId :~ ID -> id :~ REPR_TYPE -> reprType

      seq <- c.find(sel).coll[Page, Seq]()
    } yield {
      logger.debug(s"${seq.size} $reprType pages were retrieved for user: $userId of mark: $id")
      seq
    }
  }

  /** Retrieves mark's users page */
  def retrieveUserPage(userId: UUID, id: String): Future[Option[Page]] =
    retrievePage(userId, id, Representation.USERS)

  /** Retrieves mark's public page */
  def retrievePublicPage(userId: UUID, id: String): Future[Option[Page]] =
    retrievePage(userId, id, Representation.PUBLIC)

  /** Retrieve mark's private page */
  def retrieveOnePrivatePage(userId: UUID, id: String): Future[Option[Page]] =
    retrievePage(userId, id, Representation.PRIVATE)

  /** Update marks user page */
  def updateUserPage(userId: UUID, id: String, optPage: Option[Page]): Future[Unit] =
    updatePage(userId, id, Representation.USERS, optPage)

  /** Update marks public page */
  def updatePublicPage(userId: UUID, id: String, optPage: Option[Page]): Future[Unit] =
    updatePage(userId, id, Representation.PUBLIC, optPage)

  /** Remove marks users page */
  def removeUserPage(userId: UUID, id: String): Future[Unit] =
    removePage(userId, id, Representation.USERS)

  /** Remove marks public page */
  def removePublicPage(userId: UUID, id: String): Future[Unit] =
    removePage(userId, id, Representation.PUBLIC)

  /** Remove marks private pages*/
  def removePrivatePage(userId: UUID, id: String): Future[Unit] =
    removePage(userId, id, Representation.PRIVATE)

  /**  Remove mark's all pages */
  def removeAllPages(userId: UUID, id: String): Future[Unit] = {
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

  /** General method to handle update of PUBLIC and USER pages. */
  private def updatePage(userId: UUID, id: String, reprType: String, optPage: Option[Page]): Future[Unit] = {
    logger.debug(s"Updatin $reprType page for user; $userId of mark: $id")

    if (optPage.isEmpty) Future.unit else for {
      c <- dbColl()
      ur <- c.update(fkSel(userId, id, reprType), optPage.get)
      _ <- ur failIfError
    } yield {
      logger.debug(s"$reprType page was updated for user: $userId of mark: $id")
    }
  }

  /** General method to handle retrieving of PUBLIC or USERS pages. */
  private def retrievePage(userId: UUID, id: String, reprType: String): Future[Option[Page]] = {
    logger.debug(s"Retrieving $reprType representations for user: $userId of mark: $id")

    for {
      c <- dbColl()

      seq <- c.find(fkSel(userId, id, reprType)).one[Page]
    } yield {
      logger.debug(s"${seq.size} $reprType pages were retrieved fro user: $userId of mark: $id")
      seq
    }
  }

  /** General method to handle delete of PUBLIC or USERS pages. */
  private def removePage(userId: UUID, id: String, reprType: String) = {
    logger.debug(s"Removing $reprType page for user: $userId of mark: $id")

    for {
      c <- dbColl()
      rr <- c.remove(fkSel(userId, id, reprType))
      _ <- rr failIfError
    } yield {
      logger.debug(s"$reprType page was deleted")
    }
  }

  private def fkSel(userId: UUID, id: String, reprType: String): BSONDocument = {
    d :~ USR -> userId :~ ID -> id :~ REPR_TYPE -> reprType
  }
}
