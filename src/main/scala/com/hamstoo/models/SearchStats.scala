package com.hamstoo.models

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.SearchStats.{MapEl, ResultStats, Stat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.collection.GenSeqLike
import scala.collection.generic.CanBuildFrom
import scala.language.higherKinds

/**
  * Database entry model for recording search results popularity respective to their search queries. The 'map'
  * fields use list types for better translation to JSON.
  *
  * @param query    search query string for which statistics are recorded
  * @param marksMap a map with mark ids as keys and event counts as values
  * @param urlsMap  a map with urls as keys and event counts as values
  */
case class SearchStats(
                        query: String,
                        marksMap: IndexedSeq[ResultStats[String]] = IndexedSeq.empty[ResultStats[String]],
                        urlsMap: IndexedSeq[ResultStats[String]] = IndexedSeq.empty[ResultStats[String]]) {
  /* I believe Monocle library has all this case class update stuff implemented, but it was quicker to implement it
  myself with a bit of type-fu than to get to know Monocle. */
  /** Implements element update or else insert logic for lists. */
  private def mapUpdate[T, E <: MapEl[T], S[E] <: GenSeqLike[E, S[E]]](
                                                                        map: S[E],
                                                                        key: T,
                                                                        defEl: => E)
                                                                      (f: E => E)
                                                                      (implicit cbf: CanBuildFrom[S[_], E, S[E]]): S[E] =
    map.indexWhere(_.key == key) match {
      case -1 => f(defEl) +: map
      case x => map.updated(x, f(map(x)))
    }

  /** Produces incremented statistics with one more link click event. */
  def incUrl(url: String, id: String, weight: Double, index: Int): SearchStats = {
    def statInc[T]: (Stat[T]) => Stat[T] = e2 => e2.copy(urlClicks = e2.urlClicks.map(1 +) orElse Some(1))

    val statsInc: (ResultStats[String]) => ResultStats[String] = e1 => e1.copy(
      urlClicksTotal = e1.urlClicksTotal.map(1 +) orElse Some(1),
      weightsMap = mapUpdate[Double, Stat[Double], Seq](e1.weightsMap, weight, Stat(weight))(statInc),
      indexesMap = mapUpdate[Int, Stat[Int], Seq](e1.indexesMap, index, Stat(index))(statInc))
    copy(
      marksMap = mapUpdate[String, ResultStats[String], IndexedSeq](marksMap, id, ResultStats(id))(statsInc),
      urlsMap = mapUpdate[String, ResultStats[String], IndexedSeq](urlsMap, url, ResultStats(url))(statsInc))
  }

  /** Produces incremented statistics with one more full page view event. */
  def incFpv(url: String, id: String, weight: Double, index: Int): SearchStats = {
    def statInc[T]: (Stat[T]) => Stat[T] = e2 => e2.copy(fpvClicks = e2.fpvClicks.map(1 +) orElse Some(1))

    val statsInc: (ResultStats[String]) => ResultStats[String] = e1 => e1.copy(
      fpvClicksTotal = e1.fpvClicksTotal.map(1 +) orElse Some(1),
      weightsMap = mapUpdate[Double, Stat[Double], Seq](e1.weightsMap, weight, Stat(weight))(statInc),
      indexesMap = mapUpdate[Int, Stat[Int], Seq](e1.indexesMap, index, Stat(index))(statInc))
    copy(
      marksMap = mapUpdate[String, ResultStats[String], IndexedSeq](marksMap, id, ResultStats(id))(statsInc),
      urlsMap = mapUpdate[String, ResultStats[String], IndexedSeq](urlsMap, url, ResultStats(url))(statsInc))
  }
}

object SearchStats {

  trait MapEl[T] {
    val key: T
  }

  /**
    * @param key            either mark id or url, depending on which map it is
    * @param urlClicksTotal total count of mark link clicks in search results list
    * @param fpvClicksTotal total count of mark full page view visits from search results list
    * @param weightsMap     a map of mark weights in search results to mark link clicks and mark full page view visits
    * @param indexesMap     a map of mark rankings in search results considering sorting by UI to mark link clicks and
    *                       mark full page view visits
    */
  case class ResultStats[T](
                             key: T,
                             urlClicksTotal: Option[Int] = None,
                             fpvClicksTotal: Option[Int] = None,
                             weightsMap: Seq[Stat[Double]] = Seq.empty[Stat[Double]],
                             indexesMap: Seq[Stat[Int]] = Seq.empty[Stat[Int]]) extends MapEl[T]

  /**
    * @param key       either mark weight in search results or position in search results list
    * @param urlClicks count of link clicks with this key
    * @param fpvClicks count of full page view visits with this key
    */
  case class Stat[T](key: T, urlClicks: Option[Int] = None, fpvClicks: Option[Int] = None) extends MapEl[T]

  val QUERY: String = nameOf[SearchStats](_.query)
  val MRKSMAP: String = nameOf[SearchStats](_.marksMap)
  val URLSMAP: String = nameOf[SearchStats](_.urlsMap)
  val MAPKEY: String = nameOf[ResultStats[String]](_.key)
  val VISURL: String = nameOf[ResultStats[String]](_.urlClicksTotal)
  val VISFPV: String = nameOf[ResultStats[String]](_.fpvClicksTotal)
  val WGHTS: String = nameOf[ResultStats[String]](_.weightsMap)
  val INDXS: String = nameOf[ResultStats[String]](_.indexesMap)
  val STKEY: String = nameOf[Stat[_]](_.key)
  val STURL: String = nameOf[Stat[_]](_.urlClicks)
  val STFPV: String = nameOf[Stat[_]](_.fpvClicks)
  implicit val dblStatHandler: BSONDocumentHandler[Stat[Double]] = Macros.handler[Stat[Double]]
  implicit val intStatHandler: BSONDocumentHandler[Stat[Int]] = Macros.handler[Stat[Int]]
  implicit val resultStatsHandler: BSONDocumentHandler[ResultStats[String]] = Macros.handler[ResultStats[String]]
  implicit val searchStatsHandler: BSONDocumentHandler[SearchStats] = Macros.handler[SearchStats]
}
