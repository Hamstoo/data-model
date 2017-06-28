package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.UserToken
import com.hamstoo.models.UserToken.ID
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.BSONDocument

import scala.concurrent.Future

/** Data access object for confirmation tokens. */
class MongoUserTokenDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.UserToken.tokenHandler
  import com.hamstoo.utils.digestWriteResult

  private val futCol: Future[BSONCollection] = db map (_ collection "tokens")
  private val d = BSONDocument.empty
  /* Ensure mongo collection has proper index: */
  futCol map (_.indexesManager ensure Index(ID -> Ascending :: Nil))

  /** Retrieves a token by id. */
  def find(id: UUID): Future[Option[UserToken]] = for {
    c <- futCol
    optTkn <- c.find(d :~ ID -> id.toString).one[UserToken]
  } yield optTkn

  /** Saves provided token. */
  def save(token: UserToken): Future[Either[String, UserToken]] = for {
    c <- futCol
    wr <- c insert token
  } yield digestWriteResult(wr, token)

  /** Removes a token by id. */
  def remove(id: UUID): Future[Either[String, UUID]] = for {
    c <- futCol
    wr <- c remove d :~ ID -> id.toString
  } yield digestWriteResult(wr, id)
}
