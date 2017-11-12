package com.hamstoo.daos.auth

import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import play.api.Logger
import reactivemongo.api.DefaultDB

import scala.concurrent.Future

/**
  * Data access object for users' auth tokens.
  */
class MongoOAuth1InfoDao(db: () => Future[DefaultDB]) extends MongoAuthDao[OAuth1Info](db) {

  override val logger: Logger = Logger(classOf[MongoOAuth1InfoDao])
}
