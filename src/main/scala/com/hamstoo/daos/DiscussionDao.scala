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
  *
  * Discussion stored for sharing a mark
  *  and the replies back
  */

@Singleton
class DiscussionDao @Inject()(implicit db: () => Future[DefaultDB]) {


  val logger: Logger = Logger(getClass)

  // database collection
  private def dbColl(): Future[BSONCollection] = db().map(_.collection("discussions"))

  // indexes for this mongo collection
  private val indxs: Map[String, Index] =
    Index(from -> IndexType.Ascending :: to -> IndexType.Ascending ::
          subject -> IndexType.Ascending :: TIMESTAMP -> IndexType.Descending :: Nil) %
      s"bin-$from-1-$to-1-$subject-1-$TIMESTAMP-1" ::
      Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 134 seconds)


  /** Insert one email in MongoDB */
  def insert(email: Discussion): Future[Unit] = for {
      c <- dbColl()
      _ = logger.info(s"Inserting: $email")
      wr <- c.insert(email)
      _ <- wr.failIfError
    } yield logger.info(s"Successfully inserted: $email")

  /**
    * retrieve all discussions provoked by user
    * @param email
    * @return
    */
  def retrieveBySender(email: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email")
    q = d :~ from -> email
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /**
    * retrieve all discussions provoked by user having a specific topic
    * and
    * @param email
    * @param subject
    * @return
    */
  def retrieveBySender(email: String, subject: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email with subject $subject")
    q = d :~ from -> email :~ subject -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /**
    * retrieve all discussions  addressed to a user
    * @param email
    * @return
    */
  def retrieveByRecipient(email: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"emails send by: $email")
    q = d :~ to -> email
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

  /**
    * retrieve all discussions addressed to a user having a specific topic
    * and
    * @param email
    * @param subject
    * @return
    */
  def retrieveByRecipient(email: String, subject: String): Future[Seq[Discussion]] = for {
    c <- dbColl()
    _ = logger.info(s"emails received by: $email with subject $subject")
    q = d :~ to -> email :~ subject -> subject
    r <- c.find(q).sort(d :~ TIMESTAMP -> -1).coll[Discussion, Seq]()
  } yield r

}
