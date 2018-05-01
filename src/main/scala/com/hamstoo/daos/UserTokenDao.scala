/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import java.util.UUID

import com.google.inject.Inject
import com.hamstoo.models.UserToken
import com.hamstoo.models.UserToken.{EXPIRY, ID, ISSIGNUP, USR}
import org.joda.time.DateTime
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Data access object for (email and password reset) confirmation tokens.
  */
class UserTokenDao @Inject()(implicit db: () => Future[DefaultDB]) {

  import com.hamstoo.utils._

  private def dbColl(): Future[BSONCollection] = db().map(_ collection "tokens")

  // ensure mongo collection has proper indexes
  private val indxs: Map[String, Index] =
    Index(ID -> Ascending :: Nil) % s"bin-$ID-1" ::
    Index(USR -> Ascending :: ISSIGNUP -> Ascending :: Nil, unique = true) % s"bin-$USR-1-$ISSIGNUP-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 324 seconds)

  /** Retrieves a token by token ID. */
  def retrieve(tokenId: UUID): Future[Option[UserToken]] = for {
    c <- dbColl()
    optTkn <- c.find(d :~ ID -> tokenId.toString).one[UserToken]
  } yield optTkn

  /** Retrieves a token by user ID. */
  def retrieveByUserId(userId: UUID, isSignUp: Boolean): Future[Option[UserToken]] = for {
    c <- dbColl()
    optTkn <- c.find(d :~ USR -> userId.toString :~ ISSIGNUP -> isSignUp).one[UserToken]
  } yield optTkn

  /** Updates a token by token ID. */
  def update(tokenId: UUID, newExpiry: DateTime): Future[Unit] = for {
    c <- dbColl()
    wr <- c.update(d :~ ID -> tokenId.toString, d :~ "$set" -> (d :~ EXPIRY -> newExpiry.getMillis))
    _ <- wr.failIfError
  } yield ()

  /** Saves provided token. */
  def insert(token: UserToken): Future[Unit] = for {
    c <- dbColl()
    wr <- c.insert(token)
    _ <- wr.failIfError
  } yield ()

  /** Removes a token by id. */
  def remove(id: UUID): Future[Unit] = for {
    c <- dbColl()
    wr <- c.remove(d :~ ID -> id.toString)
    _ <- wr.failIfError
  } yield ()
}
