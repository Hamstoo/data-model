/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos.auth

import com.google.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import reactivemongo.api.DefaultDB

import scala.concurrent.Future

/**
  * Data access object for users' auth tokens.
  */
@Singleton
class OAuth2InfoDao @Inject()(implicit db: () => Future[DefaultDB]) extends AuthDao[OAuth2Info] {
}
