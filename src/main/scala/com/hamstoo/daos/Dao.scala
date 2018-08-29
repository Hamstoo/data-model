package com.hamstoo.daos

import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.Future

/***
  * Template for database collection DAO.
  * It will create specific logger and collection instance for super.class.
  *
  * @param db  Function to get a fresh connection from a MongoConnection pool.
  */
abstract class Dao(collName: String)(implicit db: () => Future[DefaultDB]) {

  protected val logger: Logger = Logger(getClass)

  /** Return fresh collection instance from MongoConnection pool. */
  final def dbColl: () => Future[BSONCollection] = () => db().map(_.collection(collName))
}
