package com.hamstoo.daos

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.ExpectedRating
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for MongoDB `eratings` collection.
  * @param db  Future[DefaultDB] database connection returning function.
  */
class MongoExpectedRatingDao(db: () => Future[DefaultDB])
  extends MongoReprEngineProductDao[ExpectedRating]("expected rating", db) {

  import com.hamstoo.utils._
  import com.hamstoo.models.Mark.{ID, TIMETHRU}

  val logger: Logger = Logger(classOf[MongoExpectedRatingDao])

  override def dbColl(): Future[BSONCollection] = db().map(_ collection "eratings")

  // ensure indexes
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) % s"bin-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 394 seconds)
}
