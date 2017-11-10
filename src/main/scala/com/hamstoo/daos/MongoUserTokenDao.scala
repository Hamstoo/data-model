package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserToken
import com.hamstoo.models.UserToken.ID
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


/**
  * Data access object for confirmation tokens.
  */
class MongoUserTokenDao(db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "tokens")

  // ensure mongo collection has proper index
  private val indxs = Map(Index(ID -> Ascending :: Nil) % s"bin-$ID-1")
  Await.result(dbColl() map (_.indexesManager ensure indxs), 24 seconds)

  /** Retrieves a token by id. */
  def find(id: UUID): Future[Option[UserToken]] = for {
    c <- dbColl()
    optTkn <- c.find(d :~ ID -> id.toString).one[UserToken]
  } yield optTkn

  /** Saves provided token. */
  def save(token: UserToken): Future[Unit] = for {
    c <- dbColl()
    wr <- c insert token
    _ <- wr failIfError
  } yield ()

  /** Removes a token by id. */
  def remove(id: UUID): Future[Unit] = for {
    c <- dbColl()
    wr <- c remove d :~ ID -> id.toString
    _ <- wr failIfError
  } yield ()
}
