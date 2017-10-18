package com.hamstoo.daos.auth

import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import play.api.Logger
import reactivemongo.api.DefaultDB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for users' auth tokens.
  */
class MongoOAuth2InfoDao(db: Future[DefaultDB]) extends MongoAuthDao[OAuth2Info](db.map(_.collection("users")), Logger(classOf[MongoOAuth2InfoDao]))
