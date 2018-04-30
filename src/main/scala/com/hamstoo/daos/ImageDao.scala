package com.hamstoo.daos

import com.google.inject.Inject
import com.hamstoo.models.Image
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for all images (formerly just for profile pictures), implemented with MongoDB's binary keys.
  */
class ImageDao @Inject() (db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._
  import com.hamstoo.models.Image._
  val logger: Logger = Logger(classOf[ImageDao])

  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil, unique = true) % s"bin-$ID-1-uniq" ::
    Index(MPRFX -> Ascending :: PICPRFX -> Ascending :: Nil) % s"bin-$MPRFX-1-$PICPRFX-1" ::
    Nil toMap

  private def dbColl(): Future[BSONCollection] = db().map(_.collection("userpics"))
  Await.result(dbColl().map(_.indexesManager.ensure(indxs)), 226 seconds)

  /** Saves or updates file bytes given unique ID and optional mark ID. */
  def upsert(img: Image): Future[Unit] = for {
    c <- dbColl()
    _ = logger.info(s"Upserting image ${img._id} (markId: ${img.markId}, size: ${img.pic.size})")
    //upd = d :~ "_id" -> id :~ PIC -> BSONBinary(bytes, Subtype.GenericBinarySubtype)
    wr <- c.update(d :~ ID -> img._id :~ img.markId.fold(d)(d :~ MARK_ID -> _), img, upsert = true)
    _ <- wr.failIfError
  } yield logger.info(s"Successfully upserted image")

  /** Retrieves file bytes by ID. */
  def retrieve(id: String): Future[Option[Image]] = for {
    c <- dbColl()
    mbImg <- c.find(d :~ ID -> id).one[Image]
  } yield mbImg

  /** Retrieve images for mark.  Same prefix-search-then-filter implementation as MongoUrlDuplicatesDao.retrieve. */
  def retrieve(bytes: Array[Byte], markId: ObjectId): Future[Set[Image]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving images for mark $markId")
    sel = d :~ MPRFX -> markId.binPrfxComplement :~ PICPRFX -> bytes.binaryPrefix
    candidates <- c.find(sel).coll[Image, Set]()
  } yield {
    // narrow down candidates sets to non-indexed (non-prefix) values (there should really only be 1, but
    // we use `filter` rather than `find` anyway so that data errors are not hidden)
    val imgs = candidates.filter(img => img.markId.getOrElse("") == markId && img.pic.toArray.sameElements(bytes))
    logger.info(s"${imgs.size} images were retrieved")
    imgs
  }

  // TODO: Check memory footprint and maybe try streaming
}
