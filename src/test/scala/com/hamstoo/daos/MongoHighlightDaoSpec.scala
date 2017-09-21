package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.Highlight.{HLPos, HLPreview}
import com.hamstoo.models.{Highlight, PageCoord}
import com.hamstoo.specUtils
import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class MongoHighlightDaoSpec extends Specification with Matchers{
  "MongoHighlightDai" should {
    "* return correctly sorted list of highlights" in new system {
      val usrId  = UUID.randomUUID()
      val url ="http://hamstsdsdoo.comsssd"+Random.nextFloat()

      val h1 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = PageCoord(0.5, 0.6), preview = HLPreview("", "", ""))
      val h2 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = PageCoord(0.7, 0.6), preview = HLPreview("", "", ""))
      val h3 = Highlight(usrId = usrId, url = url, pos = HLPos(Nil, 0), pageCoord = PageCoord(0.9, 0.5), preview = HLPreview("", "", ""))

      Await.result(highlightDao.create(h1), timeout) mustEqual {}
      Await.result(highlightDao.create(h2), timeout) mustEqual {}
      Await.result(highlightDao.create(h3), timeout) mustEqual {}

      Await.result(highlightDao.receive(h1.usrId), timeout).map(_.usrId) mustEqual Seq(h1.usrId, h2.usrId, h3.usrId)
    }
  }

  // https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala
  trait system extends Scope {
    val marksDao: MongoMarksDao = specUtils.marksDao
    val reprsDao: MongoRepresentationDao = specUtils.reprsDao
//    val commentsDao: MongoCommentDao = specUtils.commentDao
    val highlightDao: MongoHighlightDao = specUtils.highlightDao
    val timeout: Duration = specUtils.timeout
  }
}
