package com.hamstoo.daos

import java.util.UUID

import com.github.simplyscala.MongoEmbedDatabase
import com.hamstoo.models.Comment
import com.hamstoo.models.Comment.CommentPos
import com.hamstoo.utils.TestHelper
import de.flapdoodle.embed.mongo.distribution.Version.V3_4_1
import org.joda.time.DateTime
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers, OptionValues}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random


class MongoCommentDaoSpec
  extends FlatSpec
    with Matchers
    with ScalaFutures
    with OptionValues
    with MongoEmbedDatabase
    with TestHelper {

  implicit val pc = PatienceConfig(Span(20, Seconds), Span(1, Second))

  val time = DateTime.now.getMillis

  "MongoCommentsDao" should
    "test create comment" in {
    withEmbedMongoFixture(port = 27017, V3_4_1) { _ =>

      val commentsDao = new MongoCommentDao(getDB)

      val usrId = UUID.randomUUID()

      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()

      val oldPos = Comment.CommentPos("sdassd", "sdassd", 0, 0)
      val newPos = Comment.CommentPos("test", "test", 0.34, 0.2)
      val updatedPos = Comment.CommentPos("sdassd", "sdassd", 0.5, 0.8)

      val oldComment = Comment(usrId, url = url, pos = oldPos)

      val newComment = Comment(usrId, url = url, pos = newPos)

      commentsDao.create(oldComment).futureValue shouldEqual {}

      commentsDao.receiveAll().futureValue shouldEqual Seq(oldComment)

//      commentsDao.receive(oldComment.usrId, oldComment.id).futureValue.value shouldEqual oldComment

      commentsDao.update(oldComment.usrId, oldComment.id, pos = updatedPos).futureValue.pos shouldEqual updatedPos

      commentsDao.create(newComment).futureValue shouldEqual {}

      commentsDao.receive(url, usrId).futureValue.map(_.pos) shouldEqual Seq(CommentPos("test", "test", 0.34, 0.2), CommentPos("sdassd", "sdassd", 0.5, 0.8))

      commentsDao.delete(oldComment.usrId, oldComment.id).futureValue shouldEqual {}
    }
  }
}
