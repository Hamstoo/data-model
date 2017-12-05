package com.hamstoo.daos

import com.hamstoo.models.SearchStats
import com.hamstoo.models.SearchStats.{ResultStats, Stat}
import com.hamstoo.test.env.MongoEnvironment
import com.hamstoo.test.{FlatSpecWithMatchers, FutureHandler}
import org.scalatest.OptionValues
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.BSONDocument

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 11.11.17
  */
class MongoSearchStatsDaoTests
  extends FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with OptionValues {

  val query = "Some_query"

  val id0 = "some_id_0"
  val id1 = "some_id_1"

  val url0 = "some-test-url0"
  val url1 = "some-test-url1"

  val withUrl: SearchStats = SearchStats(
    query,
    IndexedSeq(ResultStats(id0, Some(1), None, Seq(Stat(1.0, Some(1))), Seq(Stat(1, Some(1))))),
    IndexedSeq(ResultStats(url0, Some(1), None, Seq(Stat(1.0, Some(1))), Seq(Stat(1, Some(1)))))
  )

  val withFpv: SearchStats = withUrl.copy(
    marksMap = ResultStats(id1, None, Some(1), Seq(Stat(2.0, None, Some(1))), Seq(Stat(1, None, Some(1)))) +: withUrl.marksMap,
    urlsMap = ResultStats(url1, None, Some(1), Seq(Stat(2.0, None, Some(1))), Seq(Stat(1, None, Some(1)))) +: withUrl.urlsMap
  )

  /*
   * It's helper method to check `searchstats` collection
   */
  def get(query: String): Future[Option[SearchStats]] = {
    coll.flatMap(_.find(BSONDocument("query" -> query)).one[SearchStats])
  }

  def coll: Future[BSONCollection] = db().map(_ collection "searchstats")

  "MongoSearchStatsDao" should "add url click" in {

    searchDao.addUrlClick(query, id0, url0, 1.0, 1).futureValue shouldEqual {}

    get(query).futureValue.value shouldEqual withUrl
  }

  it should "add fpv click" in {

    searchDao.addFpvClick(query, id1, Some(url1), 2.0, 1).futureValue shouldEqual {}

    get(query).futureValue.value shouldEqual withFpv

  }
}
