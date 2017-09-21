package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import com.hamstoo.utils.TestHelper
import de.flapdoodle.embed.mongo.distribution.Version

class MongoMarksDaoSpec extends TestHelper {

  lazy val marksDao = new MongoMarksDao(getDB)

  "MongoMarksDao" should "* findMissingReprs, both current and not" in {

    withEmbedMongoFixture(port = 27017, version = Version.V3_4_1) { _ =>

      val mark = Mark(UUID.randomUUID(), mark = MarkData("a subject", Some("http://hamstoo.com")))

      marksDao.create(mark).futureValue shouldEqual mark

      val newSubj = "a NEW subject"

      marksDao.update(mark.userId, mark.id, mark.mark.copy(subj = "a NEW subject")).futureValue.mark.subj shouldEqual newSubj

      marksDao.findMissingReprs(1000000).futureValue.count(_.userId == mark.userId) shouldEqual 2
    }
  }
}
