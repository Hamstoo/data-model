package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Comment, Mark, MarkData}
import com.hamstoo.specUtils
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Await
import scala.concurrent.duration._


class MongoCommentDaoSpec extends Specification {

  "MongoCommentsDao" should {

    "* test create comment" in new system {
      val usrId  = UUID.randomUUID()
      val c = Comment(usrId, url = "http://hamstsdsdoo.comsd", pos = Seq(Comment.CommentPos("sdasd","sdasd",0)))
      Await.result(commentsDao.create(c), timeout)
      Await.result(commentsDao.update(c.usrId, c.id,c.pos), timeout)
      val missingReprMarks: Seq[Comment] = Await.result(commentsDao.receive("http://hamstsdsdoo.comsd",usrId), timeout)
      missingReprMarks.count(_.usrId == c.usrId) mustEqual 2
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
