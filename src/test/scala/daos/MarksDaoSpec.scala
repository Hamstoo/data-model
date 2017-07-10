/*
package daos

import java.util.UUID

import models.{Entry, Mark}
import org.specs2.specification.Scope
import org.specs2.mutable.Specification
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future}


object MarksDaoSpec {

  lazy val testUrl = "http://www.scala-lang.org/api/2.9.3/scala/util/Try.html"
  lazy val testTags = Set("aLABEL")
  lazy val testMark = new Mark("Try a Title",
                               Some(testUrl),
                               None, // urlPrfx (gets set automatically)
                               None, // repId
                               Some(4.3),
                               Some(testTags),
                               Some("A comment."),
                               None, // hlights
                               None, // tabVisible
                               None) // tabBground
  lazy val testEntry = new Entry(UUID.randomUUID(), testMark)
}

class MarksDaoSpec extends Specification {

  // each test drops the `entries` collection from the database so they have to be run
  // sequentially, it might be better to have the tests create different users
  // so that this requirement can be relaxed (and so `dropCollection` can be removed)
  sequential // (what odd syntax)

  import DaoSpecResources._
  import MarksDaoSpec._

  // "extend Scope to be used as an Example body"
  //   [https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala]
  trait scope extends Scope {
    val marksDao: MarksDao = injector instanceOf classOf[MongoMarksDao]
    Await.ready(dropCollection(marksDao.asInstanceOf[MongoMarksDao]), TIMEOUT)
  }

  "MarksDao" should {
    "* be the beneficiary of consistently hashable `Mark`s" in /*no `new scope` necessary*/ {
      val a = Mark("xyz", None, None, None, None, None, None, None, None, None)
      val b = Mark("xyz", None, None, None, None, None, None, None, None, None)
      a.hashCode mustEqual b.hashCode
      a mustEqual b

      // whut indeed is going on with this example?  using the commented out impl will produce identical hashCodes,
      // commented out solution suggested here: https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
      case class Whut(int: Int) {
        override def hashCode: Int = super.hashCode
        //override def hashCode: Int = scala.runtime.ScalaRunTime._hashCode(this)
      }

      val c = Whut(1)
      val d = Whut(1)
      c.hashCode mustNotEqual d.hashCode // but why?!?
      c mustEqual d // at least this works as expected...
      c.hashCode mustEqual c.hashCode // ...as does this
    }

   /* "* save marks and find them by userId and entryId" in new scope {
      val fut: Future[Option[Entry]] = for {
        _ <- marksDao create testEntry
        mbEntry <- marksDao.receive(testEntry.userId, testEntry.id)
      } yield mbEntry

      val opEntry = Await.result(fut, TIMEOUT)
      opEntry.get.hashCode mustEqual testEntry.hashCode
      opEntry must beSome(testEntry)
    }*/

    "* save marks and find them by userId" in new scope {
      val fut: Future[Seq[Entry]] = for {
        _ <- marksDao create testEntry
        mbEntries <- marksDao.receive(testEntry.userId)
      } yield mbEntries
      Await.result(fut, TIMEOUT).head mustEqual testEntry
    }

    "* save marks and find them by URL and userId" in new scope {
      val fut: Future[Option[Entry]] = for {
        _ <- marksDao create testEntry
        mbEntry <- marksDao.receive(testUrl, testEntry.userId)
      } yield mbEntry
      Await.result(fut, TIMEOUT) must beSome(testEntry)
    }

    "* save marks and find them by userId and tags" in new scope {
      val fut: Future[Seq[Entry]] = for {
        _ <- marksDao create testEntry
        mbEntries <- marksDao.receiveTagged(testEntry.userId, testTags)
      } yield mbEntries
      Await.result(fut, TIMEOUT).head mustEqual testEntry
    }

    "* save marks and find labels" in new scope {
      val fut: Future[Set[String]] = for {
        _ <- marksDao create testEntry
        mbTags <- marksDao.receiveTags(testEntry.userId)
      } yield mbTags
      Await.result(fut, TIMEOUT) mustEqual testTags
    }
  }
}
*/
