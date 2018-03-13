package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Mark.{URLPRFX, USRPRFX, _}
import com.hamstoo.models.UrlDuplicate
import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedQB, ExtendedString, ExtendedWriteResult, d}
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class MongoUrlDuplicationDao(db: () => Future[DefaultDB])(implicit e: ExecutionContext) {

  val logger: Logger = Logger(classOf[MongoUrlDuplicationDao])

  private val dupsIndxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
      Index(USRPRFX -> Ascending :: URLPRFX -> Ascending :: Nil) % s"bin-$USRPRFX-1-$URLPRFX-1" ::
      Nil toMap;

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "urldups")

  Await.result(dbColl().map(_.indexesManager.ensure(dupsIndxs)), 289 seconds)

  /** Inserting duplicate in database */
  def insert(urlDup: UrlDuplicate): Future[UrlDuplicate] = {
    val userId = urlDup.userId
    logger.debug(s"Insert url duplicate for user: $userId")

    for {
      c <- dbColl()
      ir <- c.insert(urlDup)
      _ <- ir.failIfError
    } yield {
      logger.debug(s"Url duplicate was inserted for $userId")
      urlDup
    }
  }

  /** Retrieving duplicates for user */
  def retrieve(userId: UUID, url: String): Future[Set[UrlDuplicate]] = {
    logger.debug(s"Retrieving duplicates for $userId by url: $url")

    val sel = d :~ USRPRFX -> userId.toString.binPrfxComplement :~ URLPRFX -> url.binaryPrefix
    for {
      c <- dbColl()
      dups <- c.find(sel).coll[UrlDuplicate, Set]()
    } yield {
      logger.debug(s"${dups.size} was retrieved")
      dups
    }
  }

  def update(id: String, urlDuplicate: UrlDuplicate, upsert: Boolean = false): Future[Unit] = {
    logger.debug(s"Updating url dup $id...")

    for {
      c <- dbColl()
      ur <- c.update(d :~ ID -> id, urlDuplicate, upsert = upsert)
      _ <- ur.failIfError
    } yield {
      logger.debug(s"UrlDuplicate: $id was updated")
    }
  }
}
