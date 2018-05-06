package com.hamstoo.daos

import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection

import scala.concurrent.{ExecutionContext, Future}

/***
  * Template for database collection DAO.
  * It will create specific logger and collection instance for super.class.
  */
abstract class Dao(collName: String, clazz: Class[_])(implicit ex: ExecutionContext) {

  /** db instance */
  protected def db: () => Future[DefaultDB]

  protected val logger: Logger = Logger(clazz)

  /** Return fresh collection instance */
  final def dbColl: () => Future[BSONCollection] =
    () => db().map(_.collection(collName))



}
