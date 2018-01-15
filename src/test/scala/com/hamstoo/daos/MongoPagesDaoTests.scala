package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Page, Representation}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import com.hamstoo.utils.DataInfo._

class MongoPagesDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler {

  val uuid: UUID = constructUserId()
  val id: String = constructMarkId()

  val page = Page(uuid, id, Representation.PRIVATE, "Hello".toCharArray.map(_.toByte))

  "MongoPagesDao" should "insert page" in {
    pagesDao.insertPage(page).futureValue shouldEqual page
  }

  it should "retrieve private pages" in {
    pagesDao.retrievePrivateReprs(uuid, id).futureValue shouldEqual Seq(page)
  }

}
