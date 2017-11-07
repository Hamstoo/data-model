package com.hamstoo.daos

import com.hamstoo.models.Representation.Vec
import com.hamstoo.models.VectorEntry
import com.hamstoo.models.VectorEntry._
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for conceptnet-vectors API's MongoDB-based storage.  `services.Vectorizer` provides
  * additional access to this data directly via the API itself.
  */
class MongoVectorsDao(db: Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoMarksDao])

  private val futColl: Future[BSONCollection] = db map (_ collection "vectors")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(URI -> Ascending :: Nil) % s"bin-$URI-1" ::
    Nil toMap;
  futColl map (_.indexesManager ensure indxs)

  /** Saves or updates uri-vector pair. */
  def addUri(uri: String, vec: Option[Vec]): Future[Unit] = for {
    c <- futColl                                         // `URI -> uri` required b/c `upsert = true`
    upd = d :~ "$set" -> (d :~ vec.fold(d)(d :~ VEC -> _) :~ URI -> uri)
    wr <- c.update(d :~ URI -> uri, upd, upsert = true)
    _ <- wr.failIfError
    _ = logger.debug(s"Upserted vector URI '$uri'")
  } yield ()

  /** Retrieves a `VectorEntry` by conceptnet-vectors URI. */
  def retrieve(uri: String): Future[Option[VectorEntry]] = for {
    c <- futColl
    optVecEnt <- c.find(d :~ URI -> uri).one[VectorEntry]
  } yield optVecEnt
}
