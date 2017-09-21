package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Comment, PageCoord}
import com.hamstoo.specUtils
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random


class MongoCommentDaoSpec extends Specification {

  "MongoCommentsDao" should {

    "* test create comment" in new system {
      val usrId  = UUID.randomUUID()
      val url ="http://hamstsdsdoo.comsssd"+Random.nextFloat()
      val c = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd","sdassd",0,0))
      Await.result(commentsDao.create(c), timeout) mustEqual {}
      Await.result(commentsDao.update(c.usrId, c.id,c.pos), timeout).timeFrom mustNotEqual c.timeFrom
      val missingReprMarks: Seq[Comment] = Await.result(commentsDao.receive(url,usrId), timeout)
      missingReprMarks.count(_.usrId == c.usrId) mustEqual 1
    }

    "* return correctly sorted list of comments" in new system {
      val usrId  = UUID.randomUUID()
      val url ="http://hamstsdsdoo.comsssd"+Random.nextFloat()

      val c1 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd","sdassd",0,0), pageCoord = Some(PageCoord(0.5, 0.5)))
      val c2 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd","sdassd",0,0), pageCoord = Some(PageCoord(0.6, 0.5)))
      val c3 = Comment(usrId, url = url, pos = Comment.CommentPos("sdassd","sdassd",0,0), pageCoord = Some(PageCoord(0.4, 0.8)))

      Await.result(commentsDao.create(c1), timeout) mustEqual {}
      Await.result(commentsDao.create(c2), timeout) mustEqual {}
      Await.result(commentsDao.create(c3), timeout) mustEqual {}

      Await.result(commentsDao.receiveSortedByPageCoord(c1.url, c1.usrId), timeout).map(_.pageCoord) mustEqual Seq(c3.pageCoord, c2.pageCoord, c1.pageCoord)
    }
  }


  // https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala
  trait system extends Scope {
    val marksDao: MongoMarksDao = specUtils.marksDao
    val reprsDao: MongoRepresentationDao = specUtils.reprsDao
    val commentsDao: MongoCommentDao = specUtils.commentDao
    val timeout: Duration = specUtils.timeout
  }
}
