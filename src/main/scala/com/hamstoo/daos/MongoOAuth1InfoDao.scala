package com.hamstoo.daos

import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import play.api.Logger
import reactivemongo.api.DefaultDB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for users' auth tokens.
  */
class MongoOAuth1InfoDao(db: Future[DefaultDB]) extends MongoAuthDao[OAuth1Info](db.map(_.collection("users")), Logger(classOf[MongoOAuth1InfoDao]))
