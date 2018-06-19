/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos.auth

import com.google.inject.{Inject, Singleton}
import com.mohiva.play.silhouette.api.util.PasswordInfo
import reactivemongo.api.DefaultDB

import scala.concurrent.Future

/**
  * Data access object for users' password info.
  */
@Singleton
class PasswordInfoDao @Inject()(implicit db: () => Future[DefaultDB]) extends AuthDao[PasswordInfo] {
}
