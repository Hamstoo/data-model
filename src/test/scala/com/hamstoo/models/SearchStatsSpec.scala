package com.hamstoo.models

import com.hamstoo.models.SearchStats.{ResultStats, Stat}
import org.specs2.mutable.Specification

class SearchStatsSpec extends Specification {
  "SearchStats" should {
    "be incrementable with full page view events" in {
      val query = "some query"
      val id = "someId"
      val weight = 22.11
      val index = 3
      val url = "/some/url"
      val searchStats1 = SearchStats(query).incFpv(Some(url), id, weight, index)
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
      val searchStats2 = searchStats1.incFpv(None, id, weight, index)
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
      val id2 = "anotherId"
      val weight2 = 11.22
      val index2 = 2
      val url2 = "/another/url"
      val searchStats3 = searchStats2.incFpv(Some(url2), id2, weight2, index2)
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
}
