package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData, Representation}
import com.hamstoo.specUtils
import com.hamstoo.utils.{TestHelper, generateDbId}
import de.flapdoodle.embed.mongo.distribution.Version

import scala.concurrent.Await

class MongoMarksDaoSpec extends TestHelper {

  lazy val marksDao = new MongoMarksDao(getDB)

  "MongoMarksDao" should "* findMissingReprs, both current and not" in {

    withEmbedMongoFixture(port = 27017, version = Version.V3_4_1) { _ =>

      val mark = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))

      marksDao.insert(mark).futureValue shouldEqual mark

      val newSubj = "a NEW subject"

      marksDao.update(mark.userId, mark.id, mark.mark.copy(subj = "a NEW subject")).futureValue.mark.subj shouldEqual newSubj

      marksDao.findMissingReprs(1000000).futureValue.count(_.userId == mark.userId) shouldEqual 2
    }
  }

  it should "* obey its `bin-urlPrfx-1-pubRepr-1` index" in new system {
    val m = Mark(UUID.randomUUID(), mark = MarkData("crazy url subject", Some(specUtils.crazyUrl)))
    val reprId = generateDbId(Representation.ID_LENGTH)
    Await.result( for {
      _ <- marksDao.insert(m)
      _ <- marksDao.updatePublicReprId(m.id, m.timeFrom, reprId)
      mInserted <- marksDao.retrieve(m.userId, m.id)
      _ = mInserted.get.pubRepr.get shouldEqual reprId
    } yield (), timeout)
  }
}
