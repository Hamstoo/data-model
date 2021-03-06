/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Representation.Vec
import com.hamstoo.models.VectorEntry
import com.hamstoo.models.VectorEntry._
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for conceptnet-vectors API's MongoDB-based storage.  `services.Vectorizer` provides
  * additional access to this data directly via the API itself.
  */
@Singleton
class WordVectorDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[WordVectorDao])

  private def dbColl(): Future[BSONCollection] = db().map(_.collection("vectors"))

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(URI -> Ascending :: Nil) % s"bin-$URI-1" ::
    Nil toMap;
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 334 seconds)

  /** Saves or updates uri-vector pair. */
  def addUri(uri: String, vec: Option[Vec]): Future[Unit] = for {
    c <- dbColl()                                         // `URI -> uri` required b/c `upsert = true`
    upd = d :~ "$set" -> (d :~ vec.fold(d)(d :~ VEC -> _) :~ URI -> uri)
    wr <- c.update(d :~ URI -> uri, upd, upsert = true)
    _ <- wr.failIfError
    _ = logger.trace(s"Upserted vector URI '$uri'")
  } yield ()

  /** Retrieves a `VectorEntry` by conceptnet-vectors URI. */
  def retrieve(uri: String): Future[Option[VectorEntry]] = for {
    c <- dbColl()
    optVecEnt <- c.find(d :~ URI -> uri).one[VectorEntry]
  } yield optVecEnt
}
