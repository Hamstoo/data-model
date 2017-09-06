package com.hamstoo.daos

import com.hamstoo.models.Representation.Vec
import com.hamstoo.models.VectorEntry
import com.hamstoo.models.VectorEntry._
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for conceptnet-vectors service's mongo-based storage.  `services.Vectorizer` provides
  * additional access to this data directly via the service itself.
  */
class MongoVectorsDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils._

  private val futCol: Future[BSONCollection] = db map (_ collection "vectors")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(TERMS -> Ascending :: Nil) % s"bin-$TERMS-1" ::
      Index(URI -> Ascending :: Nil) % s"bin-$URI-1" ::
      Nil toMap;
  futCol map (_.indexesManager ensure indxs)

  /**
    * Saves or updates term-uri pair. If uri is None, then the term is added to a mongo document containing all
    * terms that couldn't be resolved to a conceptnet-vectors uri.
    */
  def addTerm(term: String, uri: Option[String]): Future[Unit] = for {
    c <- futCol
    wr <- c.update(d :~ URI -> uri.orElse(Some("")), d :~ "$addToSet" -> (d :~ TERMS -> term), upsert = true)
    _ <- wr failIfError
  } yield ()

  /** Saves or updates term-uri-vector tuple. */
  def addUri(term: Option[String], uri: String, vec: Option[Vec]): Future[Unit] = for {
    c <- futCol
    upd = (if (term.isDefined) d :~ "$addToSet" -> (d :~ TERMS -> term.get) else d) :~
      "$set" -> (if (vec.isDefined) d :~ VEC -> vec else d :~ URI -> uri)
    wr <- c.update(d :~ URI -> uri, upd, upsert = true)
    _ <- wr failIfError
  } yield ()

  /** Retrieves a `VectorEntry` by provided key-value. */
  private def get(key: String, s: String): Future[Option[VectorEntry]] = for {
    c <- futCol
    optVecEnt <- c.find(d :~ key -> s).one[VectorEntry]
  } yield optVecEnt

  /** Retrieves a `VectorEntry` by term. */
  def getTerm(term: String): Future[Option[VectorEntry]] = get(TERMS, term)

  /** Retrieves a `VectorEntry` by conceptnet-vectors URI. */
  def getUri(uri: String): Future[Option[VectorEntry]] = get(URI, uri)
}
