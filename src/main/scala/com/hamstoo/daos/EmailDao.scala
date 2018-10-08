/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.daos

import com.google.inject.Inject
import com.hamstoo.models.Email
import com.hamstoo.models.Email._
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
  *
  * Emails stored for sharing a mark
  *  and the replies back
  */

@Singleton
class EmailDao @Inject()(implicit db: () => Future[DefaultDB]) {


  val logger: Logger = Logger(getClass)

  // database collection
  private def dbColl(): Future[BSONCollection] = db().map(_.collection("emails"))

  // indexes for this mongo collection
  private val indxs: Map[String, Index] =
    Index(fromEmail -> IndexType.Ascending :: toEmail -> IndexType.Ascending ::
          emailSubject -> IndexType.Ascending :: TIMESTAMP -> IndexType.Descending :: Nil) %
      s"bin-$fromEmail-1-$toEmail-1-$emailSubject-1-$TIMESTAMP-1" ::
      Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 134 seconds)


  /** Insert one email in MongoDB */
  def insert(email: Email): Future[Unit] = for {
      c <- dbColl()
      _ = logger.info(s"Inserting: $email")
      wr <- c.insert(email)
      _ <- wr.failIfError
    } yield logger.info(s"Successfully inserted: $email")

  /**
    * retrieve all emails sent by user
    * @param email
    * @return
    */
  def retrieveBySender(email: String): Future[Seq[Email]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email")
    q = d :~ fromEmail -> email
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Email, Seq]()
  } yield r

  /**
    * retrieve all email sent by user having a specific subject
    * and
    * @param email
    * @param subject
    * @return
    */
  def retrieveBySender(email: String, subject: String): Future[Seq[Email]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email with subject $subject")
    q = d :~ fromEmail -> email :~ emailSubject -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Email, Seq]()
  } yield r

  /**
    * retrieve all emails received by user
    * @param email
    * @return
    */
  def retrieveByRecipient(email: String): Future[Seq[Email]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email")
    q = d :~ toEmail -> email
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Email, Seq]()
  } yield r

  /**
    * retrieve all email received by user having a specific subject
    * and
    * @param email
    * @param subject
    * @return
    */
  def retrieveByRecipient(email: String, subject: String): Future[Seq[Email]] = for {
    c <- dbColl()
    _ = logger.info(s"emails received by: $email with subject $subject")
    q = d :~ toEmail -> email :~ emailSubject -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Email, Seq]()
  } yield r

}
