/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.Inject
import com.hamstoo.models.Discussion
import com.hamstoo.models.Discussion._
import com.hamstoo.utils._
import javax.inject.Singleton
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.indexes.{Index, IndexType}

import scala.concurrent.ExecutionContext.Implicits.global
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
  // TODO: review whether indexes are being properly used
  private val indxs: Map[String, Index] =
    Index(FROM -> IndexType.Ascending :: TO -> IndexType.Ascending ::
          SUBJECT -> IndexType.Ascending :: TIMESTAMP -> IndexType.Descending :: Nil) %
      s"bin-$FROM-1-$TO-1-$SUBJECT-1-$TIMESTAMP-1" ::
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
    q = d :~ FROM -> fromEmail
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions provoked by user having a specific topic */
  def retrieveBySender(fromEmail: String, subject: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent from $fromEmail with subject '$subject'")
    q = d :~ FROM -> fromEmail :~ SUBJECT -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions addressed to a user */
  def retrieveByRecipient(toEmail: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent to $toEmail")
    q = d :~ TO -> toEmail
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /** retrieve all discussions addressed to a user having a specific topic */
  def retrieveByRecipient(toEmail: String, subject: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"Retrieving emails sent to $toEmail with subject '$subject'")
    q = d :~ TO -> toEmail :~ SUBJECT -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r
}
