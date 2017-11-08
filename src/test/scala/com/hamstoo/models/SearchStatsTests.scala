package com.hamstoo.models

import com.hamstoo.models.SearchStats.{ResultStats, Stat}
import com.hamstoo.test.FlatSpecWithMatchers

/**
  * SearchStats model unit tests
  */
class SearchStatsTests extends FlatSpecWithMatchers {

  val query = "some query"

  val id = "someId"
  val id2 = "anotherId"

  val weight = 22.11
  val weight2 = 11.22

  val index = 3
  val index2 = 2

  val url = "/some/url"
  val url2 = "/another/url"

  lazy val searchStats1: SearchStats = SearchStats(query).incFpv(Some(url), id, weight, index)
  lazy val searchStats2: SearchStats = searchStats1.incFpv(None, id, weight, index)
  lazy val searchStats3: SearchStats = searchStats2.incFpv(Some(url2), id2, weight2, index2)

  "SearchStats" should "(UNIT) be incrementable with full page view event with some url" in {

    searchStats1 shouldEqual SearchStats(
      query,
      marksMap = IndexedSeq(ResultStats(
        key = id,
        fpvClicksTotal = Some(1),
        weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(1))),
        indexesMap = Seq(Stat[Int](index, fpvClicks = Some(1))))),
      urlsMap = IndexedSeq(ResultStats(
        key = url,
        fpvClicksTotal = Some(1),
        weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(1))),
        indexesMap = Seq(Stat[Int](index, fpvClicks = Some(1))))))
  }

  it should "(UNIT) be incrementable with full page view event without url" in {
    searchStats2 shouldEqual SearchStats(
      query,
      marksMap = IndexedSeq(ResultStats(
        key = id,
        fpvClicksTotal = Some(2),
        weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(2))),
        indexesMap = Seq(Stat[Int](index, fpvClicks = Some(2))))),
      urlsMap = IndexedSeq(ResultStats(
        key = url,
        fpvClicksTotal = Some(1),
        weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(1))),
        indexesMap = Seq(Stat[Int](index, fpvClicks = Some(1))))))
  }

  it should "(UNIT) be incrementable with full page view event with other url" in {
    searchStats3 shouldEqual SearchStats(
      query,
      marksMap = IndexedSeq(
        ResultStats(
          key = id2,
          fpvClicksTotal = Some(1),
          weightsMap = Seq(Stat[Double](weight2, fpvClicks = Some(1))),
          indexesMap = Seq(Stat[Int](index2, fpvClicks = Some(1)))),
        ResultStats(
          key = id,
          fpvClicksTotal = Some(2),
          weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(2))),
          indexesMap = Seq(Stat[Int](index, fpvClicks = Some(2))))),
      urlsMap = IndexedSeq(
        ResultStats(
          key = url2,
          fpvClicksTotal = Some(1),
          weightsMap = Seq(Stat[Double](weight2, fpvClicks = Some(1))),
          indexesMap = Seq(Stat[Int](index2, fpvClicks = Some(1)))),
        ResultStats(
          key = url,
          fpvClicksTotal = Some(1),
          weightsMap = Seq(Stat[Double](weight, fpvClicks = Some(1))),
          indexesMap = Seq(Stat[Int](index, fpvClicks = Some(1))))))
  }
}
