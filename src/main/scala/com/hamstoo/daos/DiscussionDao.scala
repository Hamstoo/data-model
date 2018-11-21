/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.{Inject, Singleton}
import com.hamstoo.models.Discussion
import com.hamstoo.models.Discussion._
import com.hamstoo.utils._
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}

import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
  * Discussions are currently only replies sent to "share" emails, but in the future we may include a
  * forum section down at the bottom of singleMark/FPV displaying these discussions.  Insertions into
  * this database collection are triggered by an AWS Lambda function.
  */
@Singleton
class DiscussionDao @Inject()(implicit db: () => Future[DefaultDB]) {

  val logger: Logger = Logger(getClass)

  // database collection
  private def dbColl(): Future[BSONCollection] = db().map(_.collection("discussions"))

  // indexes for this mongo collection
  // TODO: indexes are too complex to be properly used by current queries
  private val indxs: Map[String, Index] =
    Index(SENDR -> IndexType.Ascending :: RECIP -> IndexType.Ascending ::
          MARKID -> IndexType.Ascending :: TIMESTAMP -> IndexType.Descending :: Nil) %
      s"bin-$SENDR-1-$RECIP-1-$MARKID-1-$TIMESTAMP-1" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 135 seconds)

  /** Insert one email in MongoDB */
  def insert(email: Discussion): Future[Unit] = for {
      c <- dbColl()
      _ = logger.info(s"Inserting: $email")
      wr <- c.insert(email)
      _ <- wr.failIfError
    } yield logger.info(s"Successfully inserted: $email")

  /** retrieve all discussions provoked by user */
  def retrieveBySender(fromEmail: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent from $fromEmail")
    q = d :~ SENDR -> fromEmail
    r <- c.find(q, Option.empty[Discussion]).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions provoked by user having a specific markId */
  def retrieveBySender(fromEmail: String, markId: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent from $fromEmail with subject '$markId'")
    q = d :~ SENDR -> fromEmail :~ MARKID -> markId
    r <- c.find(q, Option.empty[Discussion]).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions addressed to a user */
  def retrieveByRecipient(toEmail: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent to $toEmail")
    q = d :~ RECIP -> toEmail
    r <- c.find(q, Option.empty[Discussion]).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions addressed to a user having a specific markId */
  def retrieveByRecipient(toEmail: String, markId: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent to $toEmail with subject '$markId'")
    q = d :~ RECIP -> toEmail :~ MARKID -> markId
    r <- c.find(q, Option.empty[Discussion]).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r
}
