package com.hamstoo.daos.auth

import com.mohiva.play.silhouette.api.util.PasswordInfo
import play.api.Logger
import reactivemongo.api.DefaultDB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for users' password info.
  */
class MongoPasswordInfoDao(db: () => Future[DefaultDB]) extends MongoAuthDao[PasswordInfo](db) {

  override val logger = Logger(classOf[MongoPasswordInfoDao])
}
