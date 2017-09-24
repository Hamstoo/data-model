package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Comment, PageCoord, Sortable}
import com.hamstoo.utils.TestHelper

import scala.util.Random

class MongoCommentDaoSpec extends TestHelper {

  lazy val commentsDao = new MongoCommentDao(getDB)

  "MongoCommentsDao" should "* test create comment" in  {
    withEmbedMongoFixture() { _ =>

      val usrId = UUID.randomUUID()
      val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()
      val c = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd", "sdassd", 0, 0))

      commentsDao.create(c).futureValue shouldEqual {}

      commentsDao.update(c.usrId, c.id, c.pos).futureValue.timeFrom should not equal c.timeFrom

      commentsDao.receive(url, usrId).futureValue.count(_.usrId == c.usrId) shouldEqual 1
    }
  }

    it should "* return correctly list of comments for specifyed user" in {
      withEmbedMongoFixture() { _ =>

        val usrId = UUID.randomUUID()
        val url = "http://hamstsdsdoo.comsssd" + Random.nextFloat()

        val c1 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.5, 0.5)))
        val c2 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.6, 0.5)))
        val c3 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd", "sdassd", 0, 0), pageCoord = Some(PageCoord(0.4, 0.8)))

        commentsDao.create(c1).futureValue shouldEqual {}
        commentsDao.create(c2).futureValue shouldEqual {}
        commentsDao.create(c3).futureValue shouldEqual {}

        commentsDao.receive(c1.url, c1.usrId).futureValue
          .sortWith(Sortable.sort)
          .map(_.shortcut)
          .map(_.preview) shouldEqual Seq(c1.pos.text, c2.pos.text, c3.pos.text)
      }
    }
}
