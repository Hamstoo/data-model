package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserToken
import com.hamstoo.models.UserToken.ID
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Data access object for confirmation tokens. */
class MongoUserTokenDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.UserToken.tokenHandler
  import com.hamstoo.utils.{ExtendedIM, ExtendedIndex, ExtendedWriteResult}

  private val futCol: Future[BSONCollection] = db map (_ collection "tokens")
  private val d = BSONDocument.empty
  /* Ensure mongo collection has proper index: */
  private val indxs = Map(Index(ID -> Ascending :: Nil) % s"bin-$ID-1")
  futCol map (_.indexesManager ensure indxs)

  /** Retrieves a token by id. */
  def find(id: UUID): Future[Option[UserToken]] = for {
    c <- futCol
    optTkn <- c.find(d :~ ID -> id.toString).one[UserToken]
  } yield optTkn

  /** Saves provided token. */
  def save(token: UserToken): Future[Unit] = for {
    c <- futCol
    wr <- c insert token
    _ <- wr failIfError
  } yield ()

  /** Removes a token by id. */
  def remove(id: UUID): Future[Unit] = for {
    c <- futCol
    wr <- c remove d :~ ID -> id.toString
    _ <- wr failIfError
  } yield ()
}
