/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import java.util.Locale

import com.google.inject.Inject
import com.hamstoo.daos.RepresentationDao.{CONTENT_WGT, KWORDS_WGT}
import com.hamstoo.models.Representation
import com.hamstoo.models.Representation.{Vec, VecEnum, _}
import com.hamstoo.utils
import play.api.Logger

import scala.annotation.tailrec
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import scala.concurrent.Future
import scala.util.Random
import scala.util.matching.Regex


object VectorEmbeddingsService {

  val logger = Logger(getClass)

  /**
    * A word's `mass` can be thought of as its TF-IDF or BM25 score.  `tf` is a function of `count` such as
    * the TF portion of the BM25 formula or log(count) or sqrt(count) as used by Lucene.
    */
  case class WordMass(word: String, count: Double, tf: Double, mass: Double, scaledVec: Vec)

  // first of the pair is a map from query word to count, second is just what it says
  type WordCountsType = Seq[(String, Int)]
  type WordMassesType = Future[Seq[WordMass]]
  type Query2VecsType = (WordCountsType, WordMassesType)

  // avg(5000, 7200, 2200, 5300, 1077, 3400) see second HTMLRepresentationServiceSpec test
  val MEDIAN_DOC_LENGTH = 4000

  /**
    * This function computes a BM25 "term frequency" which really isn't a term frequency though it is used in
    * place of the term frequency in a TF-IDF model.
    * @param tf  When coming from SearchResults, this is a MongoDB Text Index search score, typically 4.0 or 8.0.
    */
  def bm25Tf(tf: Double, docLength: Double, b: Double = 0.5, k1: Double = 1.2): Double = {

    // "setting B to 0.5 here, which introduces a slight bias back towards" long documents
    //  [http://www.benfrederickson.com/distance-metrics/]
    //val b = 0.5
    val lengthNorm = (1.0 - b) + b * docLength / MEDIAN_DOC_LENGTH

    // "The usual value of K1 used in text search is around 1.2, which makes sense for text queries as its more
    // important to match documents containing all of the terms in the query instead of matching repeated terms."
    //val k1 = 1.2
    tf * (k1 + 1.0) / (k1 * lengthNorm + tf)
  }

  /**
    * BM25 is tricky, hence this variation.
    * @param tf  When coming from SearchResults, this is a MongoDB Text Index search score, typically 4.0 or 8.0.
    */
  def logTf(tf: Double, docLength: Double): Double = {
    math.log(1.0 + tf / math.sqrt(docLength / MEDIAN_DOC_LENGTH))
  }

  /**
    * Given a word vector and a map of various vector types (that were all constructed from a single
    * document) compute the aggregate similarity of the word to the document.
    * @param chooseMax  Boolean (default: false).  `aggregateSimilarityScore` will be used if false; max similiarity
    *                   will be used if true (and in which case all `docVecs` are considered, not just those
    *                   used in `aggregateSimilarityScore`).
    */
  def documentSimilarity(wordVec: Vec,
                         docVecs: Map[Representation.VecEnum.Value, Vec],
                         chooseMax: Boolean = false): Double = {

    // principal axes are (typically) adirectional (though we attempt to directionalize them when they
    // are constructed in repr-engine) so allow negatives but with a penalty
    val sims: Map[VecEnum.Value, Double] = docVecs.flatMap { case (vecType, docVec) =>
      val cos = wordVec cosine docVec
      vecType match {
        // TODO: should this math.max be removed now that we're directionalizing PC vecs? see mbOrientationVec below
        case vt if vt.toString.startsWith("PC") => Some(vt -> math.max(cos, -0.95 * cos))
        case vt => Some(vt -> cos)
      }
    }

    //logger.debug(s"${wm.word}")
    //sims.toSeq.sortBy(_._1).foreach { case (t, s) => logger.debug(f"  $t = $s%.2f") }
    //logger.debug(f"    aggregateSimilarityScore(${wm.word}) = ${aggregateSimilarityScore(sims)}%.2f")

    if (chooseMax) maxSimilarityScore(sims) else aggregateSimilarityScore(sims)
  }

  /**
    * See kwsSimilarities.xlsx for an approximate fit of this model (R^2 ~= 12.2%).
    *
    * Note:
    *   1. there is an attempt made at directionalizing the PC vectors in VectorEmbeddingsService.text2PcaVecs
    *   2. the KM vectors (especially KM2 and KM3) are unstable
    *   3. the PC vectors (columns) are demeaned first anyway, effectively equivalent to IDF-weighted, so maybe
    *      IDF-weighted is a better first "component" (i.e. think "market factor")
    *
    * For an example of this, see the "compute principal axes" test case.  Furthermore, a decent approximation
    * of `aggregateSimilarityScore` might just be IDF + PC1, weighted evenly.
    */
  def aggregateSimilarityScore(sims: Map[VecEnum.Value, Double]): Double = {
    0.47 * sims.getOrElse(VecEnum.IDF, 0.0) + // t-stat ~= 3.7
    1.22 * sims.getOrElse(VecEnum.PC1, 0.0) + //           7.1
    0.84 * sims.getOrElse(VecEnum.PC2, 0.0) + //           5.0
    0.66 * sims.getOrElse(VecEnum.PC3, 0.0) + //           3.4
   -0.28 * sims.getOrElse(VecEnum.KM1, 0.0)   //          -2.6
  }

  /**
    * Rather than selecting keywords based on a single function, why not select them based on their highest
    * correlation to any one of the document (or user) vecs.  That way a more diverse set of words might be selected.
    */
  def maxSimilarityScore(sims: Map[VecEnum.Value, Double]): Double =
    if (sims.isEmpty) -1.0 else sims.values.max

  /** Converts from number of unique words in a document to the number that are desired by `text2TopWords`. */
  def defaultNumTopWords(nUnique: Int): Int = {

    // 100% for a 5-word (or fewer) document, 35% for a 50-word, 11% for a 400-word (see repr-engine/topWords.xlsx)
    def desiredFraction(n: Int): Double = if (n < 1) 1.0 else 0.6955 + 7.2908e-5*n + 2.3625/n + -0.1035*math.log(n)
    //(5 to 1000 by 50).foreach { x => println(f"$x -> ${desiredFracWords(x)}") } // debugging

    val fracDesired = desiredFraction(nUnique)

    // intentionally use very few words so that there aren't too many represented by each PC
    val maxWords = 75
    val nDesired = math.min(maxWords, fracDesired * nUnique).toInt
    logger.info(f"Document word mass stats: nUnique = $nUnique, nTop = $nDesired (${fracDesired*100}%.1f%%) (${utils.memoryString})")
    nDesired
  }

  /**
    * Given vector representations of a document, generate semantic keywords for that document.  Such keywords
    * are the ones most highly correlated to the document's vector representations.
    *
    * @param docVecs  Document vectors.
    * @param candidates  Candidate words to match document vectors against.
    * @return  The 15 words with the highest `aggregateSimilarityScore`s.
    */
  def keywords(docVecs: Map[Representation.VecEnum.Value, Vec], candidates: Seq[WordMass]): Seq[String] = {

    // debugging (this commented-out code will produce a file for use by kwsSimilarities.xlsx, also
    // see HTMLRepresentationServiceSpec)
    /*if (true) {
      import java.io.{BufferedWriter, FileOutputStream, OutputStreamWriter}
      utils.cleanlyWriteFile("kwsSimilarities.csv") { fp =>

        var first = true
        topWords.sortBy(-_.mass).foreach { wm =>
          val sims = vecs.flatMap { case (vecType, docVec) =>
            val cos = wm.scaledVec cosine docVec
            vecType match {
              case vt if vt.toString.startsWith("PC") => Some(vt -> math.max(cos, -0.95 * cos))
              case vt => Some(vt -> cos)
            }
          }

          val seqSims = sims.toSeq.sortBy(_._1) // sort by `vecType`

          if (first) {
            first = false
            fp.write("TERM,count,bm25Tf,IDF,aggSS," + seqSims.map(_._1.toString).mkString(","))
          }
          fp.write(s"\n${wm.word},${wm.count},${wm.tf},${idfModel.transform(wm.word)},${aggregateSimilarityScore(sims)}")
          seqSims.foreach { case (_, cos) => fp.write(f",$cos%.2f") }
        }
      }
    }*/

    // convert to (word, similarity) pairs
    val wordsUnsorted = candidates.map { wm => wm.word -> documentSimilarity(wm.scaledVec, docVecs, chooseMax = true) }
    keywords(wordsUnsorted)
  }

  // select 15 highest scoring words (with some filtering of words with only a few letters)
  val N_DESIRED_KEYWORDS = 15

  /** Used to be part of the other `keywords` method.  Moved here to be accessible from outside. */
  def keywords(wordsUnsorted: Seq[(String, Double)]): Seq[String] = {

    // sort by `aggregateSimilarityScore` (descending)
    val words = wordsUnsorted.sortBy(-_._2).filterNot(ws => Seq("http", "https").contains(ws._1))
    if (logger.isDebugEnabled)
      words.foreach { case (w, s) => logger.debug(f"keywords: $w -> $s%.3f")}

    // not quite sure how to do what follows in a functional programming style
    val selections = scala.collection.mutable.ListBuffer.empty[String]

    // don't allow any 1- or 2- letter words, only allow a single 3-letter word, etc.
    val wordLengthLimits = IndexedSeq(0, 0, 1, 2, 4)

    words.foreach { case (w, _) => if (selections.lengthCompare(N_DESIRED_KEYWORDS) < 0) {

      // if the plural of the word is already in the list, then replace it
      if (selections.contains(w + "s")) {
        val i = selections.indexOf(w + "s")
        selections(i) = w
      } else if (w.endsWith("s") && selections.contains(w.init)) {
        // do nothing

      } else if (selections.contains(w + "ing")) {
        val i = selections.indexOf(w + "ing")
        selections(i) = w
      } else if (w.endsWith("ing") && selections.contains(w.dropRight(3))) {
        // do nothing

      } else if (w.endsWith("y") && selections.contains(w.init + "ies")) {
        val i = selections.indexOf(w.init + "ies")
        selections(i) = w
      } else if (w.endsWith("ies") && selections.contains(w.dropRight(3) + "y")) {
        // do nothing

      } else {
        // count the number of words of this limit in the list so far
        val limit = wordLengthLimits.lift(w.length - 1).getOrElse(N_DESIRED_KEYWORDS)
        if (selections.count(_.length == w.length) < limit)
          selections += w
      }
    }}

    selections
  }
}

/**
  * This service, via its `vectorEmbeddings` method, generates several different kinds of semantic vector
  * representations for documents.  Each vector representation is based on a different clustering algorithm.
  * The goal of each algorithm is to identify clusters of a document's words containing high TF-IDF words
  * and from each cluster compute an average of its words' vectors as a representation of the document.
  */
@com.google.inject.Singleton // Guice Singleton, not Java Singleton, one per Injector instance, not one per process
class VectorEmbeddingsService @Inject()(vectorizer: Vectorizer, idfModel: IDFModel) {

  import VectorEmbeddingsService._

  // for testing only
  var wCount: Int = 0
  //var slCount: Int = 0 // Vectorizer.sAndL has been deprecated

  /**
    * Join the various string representations into a single string and weight (or repeat) each of them so that
    * each contains approximately the same number of words.  Then weight them again according to their MongoDB
    * "text index" search weights.
    *
    * @return  A future document's weighted word counts and respectively weighted word vectors.
    */
  def weightedTopWords(hd: String, dt: String, ot: String, kw: String): Future[(Seq[WordMass], Long)] = {

    // these weights have different effective behaviors here than during Mongo searches because here the
    // vectors all have the same L2 norm while searching through the document text in Mongo effectively
    // makes longer texts more relevant than shorter b/c they contain more words (also, don't use a Map here
    // just on the very off chance that two of the texts are equal, e.g. see the unit test)
    val strreprs = Seq(hd -> CONTENT_WGT, dt -> CONTENT_WGT, ot -> 1, kw -> KWORDS_WGT)

    // first weight them all approximately the same
    val maxLen = strreprs.map(_._1.length).max

    // word count is used to normalize $search scores obtained from queries against MongoDB Text Index
    var nWords: Long = 0

    lazy val defaultSeqWM: WordMassesType = Future.successful(Seq.empty[WordMass])

    val seq: WordMassesType = if (maxLen == 0) defaultSeqWM else {
      Future.sequence(strreprs.map { case (str, wgt) =>
        // normalize w.r.t. cubrt(char count ratio) b/c `doctext` can be many, many times longer than the others
        if (str.isEmpty) defaultSeqWM else {

          val r = wgt * math.pow(maxLen.toDouble / str.length, 0.333333)

          // it helps for this to be synchronized because countWords via text2TopWords can issue a massive number of
          // database calls all at the same time (e.g. 50 marks being processed for representations each with 500 words
          // each needing vector lookups) leading to an unrecoverable cascade of TimeoutExceptions/DriverExceptions

          text2TopWords(str).map { case (topWords, docLength) =>
            nWords += docLength * wgt // this weighting gets "undone" below
            topWords.map(wm => WordMass(wm.word, wm.count * r, wm.tf * r, wm.mass * r, wm.scaledVec * r))
          }
        }
      }).map(_.flatten)
        .map(_.groupBy(_.word))
        .map(_.map { case (w: String, wms: Seq[WordMass]) =>
          wms reduce[WordMass] {
            case (a, b) => WordMass(w, a.count + b.count, a.tf + b.tf, a.mass + b.mass, a.scaledVec + b.scaledVec)
          }
        })
        .map(_.toSeq)
    }

    // undo the nWords weighting as if all of the words were straight up doctext
    seq.map(s => s -> nWords / CONTENT_WGT)
  }

  /**
    * Generate multiple word/vector embeddings from the text representations of the document, one for each
    * of the `Representation.VecEnum`s.
    *
    * @return  3-tuple of:
    *            (1) vectors (for each `VecEnum` type)
    *            (2) keywords (computed from all of them)
    *            (3) aggregate number of words (adjusted for weights in `weightedTopWords`)
    */
  def vectorEmbeddings(hd: String, dt: String, ot: String, kw: String):
                                      Future[(Map[Representation.VecEnum.Value, Vec], Seq[String], Long)] = {

    weightedTopWords(hd, dt, ot, kw).map { case (topWords, nWords) => // calculate future result

      //val crpVecs: (Option[Vec], Option[Vec]) = text2CrpVecs(topWords)
      val idfVecs: Option[(Vec, Vec)] = text2IdfVecs(topWords)
      val pcVecs: Seq[Vec] = text2PcaVecs(topWords, 4, idfVecs.map(_._1))

      val (kmVecs0, loss0) = text2KMeansVecs(topWords, 5) // compute 5 clusters but only use best 3 of them
      val (kmVecs1, loss1) = text2KMeansVecs(topWords, 5) // and compute the 5 clusters 3 times also ...
      val (kmVecs2, loss2) = text2KMeansVecs(topWords, 5) // ... to choose the one with the lowest loss
      val kmVecs = if (loss0 < loss1 && loss0 < loss2) kmVecs0
              else if (loss1 < loss0 && loss1 < loss2) kmVecs1 else kmVecs2

      // TODO: should pcVecs be calculated from vectors that are residualized wrt the previously calculated vectors?

      // Error:
      //   diverging implicit expansion for type scala.collection.generic.CanBuildFrom[
      //     com.hamstoo.models.Representation.VecEnum.ValueSet,
      //     (com.hamstoo.models.Representation.VecEnum.Value, com.hamstoo.models.Representation.Vec),
      //     That]
      //   [error] starting with method orderingToOrdered in object Ordered
      //   [error]       val x: Set[(VecEnum.Value, Vec)] = VecEnum.values.flatMap {
      // Solution:
      //   Add `toList` per this:
      //     "Something about that being a Set did not agree with how you were attempting to convert it to a Map"
      //     [https://stackoverflow.com/questions/16444158/scala-diverging-implicit-expansion-when-using-tomap]
      val vecreprs = VecEnum.values.toList.flatMap {
        case vt if vt == VecEnum.CRPv2_max => None // crpVecs._1.map(vt -> _)
        case vt if vt == VecEnum.CRPv2_2nd => None // crpVecs._2.map(vt -> _)
        case vt if vt == VecEnum.IDF => idfVecs.map(vt -> _._1)
        case vt if vt == VecEnum.IDF3 => idfVecs.map(vt -> _._2)
        case vt if vt.toString.startsWith("PC") || vt.toString.startsWith("KM") =>
          val rgx = raw"([PK][CM])(\d+)".r
          val rgx(pk, i) = vt.toString // extractor
          (if (pk == "PC") pcVecs else kmVecs).lift(i.toInt - 1).map(vt -> _)
      }.toMap.mapValues(_.l2Normalize)

      (vecreprs, keywords(vecreprs, topWords), nWords)
    }
  }

  /**
    * Converts a document text to a vector by a weighted average of its words' vectors via 2 methods:
    *   1. IDF-weighted words
    *   2. IDF^3-weighted words
    */
  def text2IdfVecs(topWords: Seq[WordMass]): Option[(Vec, Vec)] = {

    // "You only really want to use `fold` when your `A1` really is a super-type of `A`"
    //  [https://stackoverflow.com/questions/25066863/fold-collection-with-none-as-start-value]
    topWords.foldLeft(Option.empty[(Vec, Vec)]) { case (agg, wm) =>

      val idf = idfModel.transform(wm.word)
      val newVecs = (wm.scaledVec,
                     wm.scaledVec * (idf * idf)) // `scaledVec` has already been multiplied by `idf` once
      agg match {
        case None => Some(newVecs)
        case Some((v1, v2)) => Some((v1 + newVecs._1, v2 + newVecs._2))
      }
    }
  }

  /**
    * Chinese Restaurant Process (stochastic) clustering algorithm
    * "Note that this is a simple 1-pass clustering process and we don’t have to specify number of
    * clusters! Could be very helpful for latency sensitive services."
    * [https://medium.com/kifi-engineering/from-word2vec-to-doc2vec-an-approach-driven-by-chinese-restaurant-process-93d3602eaa31]
    * Are there other, fast, one-pass clustering algorithms?
    * Such as this: http://proceedings.spiedigitallibrary.org/proceeding.aspx?articleid=2475560
    * Returns the top 2 clusters per average IDF computed over all words in the clusters.
    */
  def text2CrpVecs(topWords: Seq[WordMass]): (Option[Vec], Option[Vec]) = {

    /**
      * Class for storing clusters from the clustering algorithm below.
      * The linked Medium article doesn't explain how to choose the most important cluster--that's what
      * `idfSum` is for.
      */
    case class Cluster(words: Set[String],
                       vecSum: Vec,
                       var massSum: Double = 0.0) {
      def +(word: String, vec: Vec, mass: Double): Cluster = Cluster(words + word, vecSum + vec, massSum + mass)
    }

    /** Compute Chinese Restaurant Process clusters. */
    @tailrec
    def crp(topWords: Seq[WordMass], clusters: Seq[Cluster] = Seq.empty[Cluster]): Seq[Cluster] = {
      if (topWords.isEmpty) clusters else {

        val wm = topWords.head

        val cs = if (clusters.isEmpty) Cluster(Set(wm.word), wm.scaledVec) :: Nil else {
          val (maxCos, argmax) = clusters.view.map(_.vecSum cosine wm.scaledVec).zipWithIndex.maxBy(_._1)
          val n = clusters.length
          val p = 1.0 / (1 + n)

          // "If sim(V, C) > 1/(1 + n), goes to cluster C. Otherwise with probability 1/(1+n) it creates a
          // new cluster and with probability n/(1+n) it goes to C." -- so as n increases, the probability
          // of creating a new cluster decreases -- e.g. once n gets up to 9 it's very likely that there will
          // be an existing cluster with cosine similarity of at least 0.1
          if (maxCos > p || Random.nextDouble > p) // stochastic
            clusters.updated(argmax, clusters(argmax) + (wm.word, wm.scaledVec, wm.mass))
          else
            clusters :+ Cluster(Set(wm.word), wm.scaledVec)
        }

        crp(topWords.drop(1), cs)
      }
    }

    val clusters = crp(topWords)

    // debugging
    /*if (true) {
      println(s"\nCLUSTERS (N = ${clusters.size}) for text: '${text.take(100)}...'")
      import java.io.{BufferedWriter, FileOutputStream, OutputStreamWriter}
      utils.cleanly(new BufferedWriter(new OutputStreamWriter(new FileOutputStream("clusters.csv"))))(_.close) { fp =>
        clusters.sortBy(c => -c.idfMax * c.idfSum / c.words.size).foreach { c =>
          println(c.words)
          println(f"    WORDS: N = ${c.words.size}; IDFS: AVG = ${c.idfSum / c.words.size}%.3f, MAX = ${c.idfMax}%.3f" +
          f", N_MAX = ${c.nMaxIdfs}, sqrt(AVG*MAX) = ${math.sqrt(c.idfMax * c.idfSum / c.words.size)}%.3f")
          fp.write(c.words.head)
          c.vecSum.l2Normalize.foreach { d => fp.write(f",$d") }
          fp.write("\n")
        }
      }
      //utils.tokenize(text).foreach { w =>
      val str =
        "inevitable stagnation despotic rage providing species absorbs conventional serious youthful rate nonetheless"
      str.split(" ").foreach { w =>
        vectorizer.dbCachedLookup(Locale.ENGLISH, w)
          .foreach { case (v, _) =>
            val cosines = clusters.map(_.vecSum cosine v).map(cos => f"$cos%.2f")
            println(s"WORD: $w -> COSINES: $cosines")
          }
      }
    }*/

    clusters match {
      case Nil => (None, None)
      case head :: Nil => (Some(head.vecSum), None)
      case _ =>
        val sorted = clusters.sortBy(c => -c.massSum / c.words.size)
        (Some(sorted.head.vecSum), Some(sorted(1).vecSum))
    }
  }

  /**
    * Count words in the document that have word vectors.
    */
  def countWords(words: Seq[String]): Future[Map[String, (Int, Vec)]] = {
    logger.debug(s"Counting words ($wCount, ${Vectorizer.dbCount}, ${Vectorizer.fCount})")

    // count number of occurrences of each (non-standardized) word so that we only have to lookup a vec for each once
    val grouped0: WordCountsType = words.groupBy(identity).mapValues(_.length).toSeq

    /**
      * This is an older implementation of countWords that doesn't make use of ReactiveMongo's asynchronicity
      * but rather issues all of the futures sequentially.  It was originally re-implemented while debugging
      * issue #202, but it wasn't the cause of the bug.  Still, it may be useful for debugging again in the future
      * so I'm leaving it commented-out here for that purpose.
      */
    //@tailrec
    def rec(grouped: WordCountsType, wordCounts: Map[String, (Int, Vec)] = Map.empty[String, (Int, Vec)]):
                                              Future[Map[String, (Int, Vec)]] = {

      if (grouped.isEmpty) Future.successful(wordCounts) else {

        // call dbCachedLookupFuture for each non-standardized/raw word in `grouped`
        // TODO: it would be nice if we could standardize the word first before calling `dbCachedLookup`
        // TODO: this would cut down on re-querying vectors for words that have already been found
        vectorizer.dbCachedLookupFuture(Locale.ENGLISH, grouped.head._1)
          .map { mbWordVec =>
            mbWordVec.collect { case (vec, standardizedWord) =>
              val updt = wordCounts.getOrElse(standardizedWord, (0, vec))
              wCount += 1 // not threadsafe, but just for testing, so who cares
              wordCounts.updated(standardizedWord, (updt._1 + grouped.head._2, vec))
            }.getOrElse(wordCounts)
          }
          .flatMap { wcs => rec(grouped.drop(1), wcs) }
      }
    }

    rec(grouped0)

    // call dbCachedLookupFuture for each non-standardized/raw word in `words`
    /*val withVecs = grouped0.map { case (w, n) =>

      // TODO: it would be nice if we could standardize the word first before calling `dbCachedLookup`
      // TODO: this would cut down on re-querying vectors for words that have already been found
      vectorizer.dbCachedLookupFuture(Locale.ENGLISH, w).map {
        _.map { case (vec, standardizedWord) =>
          wCount += 1 // not threadsafe, but just for testing, so who cares
          (standardizedWord, (n, vec))
        }
      }
    }

    // Iterable-Future swap
    val fseq: Future[Iterable[Option[(String, (Int, Vec))]]] = Future.sequence(withVecs)

    // group by standardized words (just in case any non-standardized words map to the same standardized word)
    fseq.map(_.flatten.groupBy(_._1).mapValues(it => (it.map(_._2._1).sum, it.head._2._2)))*/
  }

  // regex that matches all possible valid word delimiters, this includes various punctuation possibly followed or
  // prepended by spaces, it's used in the following word-matching regex and for these spacers replacement later
  val spcrRgx: String = """[-\/_\+—]|(\.\s+)|([\s,:;?!…]\s*)|(\.\.\.\s*)|(\s*["“”\(\)]\s*)"""

  // regex that matches all latin alphabet words, separated by all kinds of spacers.
  val termRgx: Regex = s"[^a-z]*([a-z]+(($spcrRgx|[\\.'’])[a-z]+)*+)".r.unanchored

  /**
    * For a given query string, compute WordMasses, which include weighted word vectors, for each de-duplicated
    * word in the string.
    */
  def query2Vecs(query: String): Query2VecsType = {

    // don't use a Set here, allow words to be specified more than once as a potential way for users to tailor queries
    val cleanedQuery: WordCountsType = utils.tokenize(utils.parse(query).replaceAll("\"", ""))
      .filter(_.nonEmpty)
      .flatMap(termRgx.findFirstMatchIn)
      .map(_.group(1).replaceAll("’", "'").replaceAll(s"($spcrRgx)+", ""))
      .groupBy(identity).mapValues(_.size) // count occurrences of each word
      .toSeq // must be converted from a map to a seq b/c it gets zipped with other parallel seqs down below

    // cleaned query words with the same number of original occurrences joined back into a single string
    val rejoinedQuery = cleanedQuery.map { case (w, n) => (w + " ") * n }.mkString(" ")

    // get vectors for all terms (per `identity`) in search query
    (cleanedQuery, text2TopWords(rejoinedQuery, identity).map(_._1))
  }

  /**
    * Choose top words (based on BM25 score) to be used in the various clustering and word vector aggregation
    * algorithms.  Generally speaking, we're trying to select those words that are indicative of the essence
    * of a document, so we only choose at most 75 words depending on document size.
    *
    * @param txt  The text to be tokenized and top words pulled from.
    * @param numTopWords  Function that converts from number of unique words to a fraction of which to return.
    * @return  Returns highest scoring `desiredFracWords` fraction of unique words as ordered by BM25 score.
    */
  def text2TopWords(txt: String, numTopWords: Int => Int = defaultNumTopWords): Future[(Seq[WordMass], Long)] = {

    // this is the only place that `countWords` (which is what calls `vectorizer.dbCachedLookup`) should be called
    countWords(utils.tokenize(txt)).map { counts =>

      // this is a "true" docLength w/out any weights applied to normalize as is performed in weightedTopWords
      val docLength: Long = counts.foldLeft(0.toLong) { case (agg, (_, (n, _))) => agg + n }

      // debugging
      /*if (true) {
        import java.io.{BufferedWriter, FileOutputStream, OutputStreamWriter}
        utils.cleanlyWriteFile("wordCounts.csv") { fp =>
          wordCounts.toSeq.sortBy(wn => -wn._2._1 * idfModel.transform(wn._1))
            .foreach { case (w, (n, v)) =>
              fp.write(s"$w,$n,${idfModel.transform(w)}\n")
              println(s"$w: $n * ${idfModel.transform(w)} = ${n * idfModel.transform(w)} ")
            }
        }
      }*/

      // scale the TFs by their IDFs (i.e. "mass") and the word vectors by the resulting product
      val withIdfs = counts.toSeq.map { case (w, (n, v)) =>
        val tf = bm25Tf(n, docLength) // "true" docLength
        val mass = tf * idfModel.transform(w)
        logger.trace(f"text2TopWords.withIdfs: WordMass($w, $n, $tf%.2f * ${mass / tf}%.2f = $mass%.2f)")
        WordMass(w, n, tf, mass, v * mass)
      }

      val nDesired = numTopWords(counts.size)
      val topWords = withIdfs.sortBy(-_.mass).take(nDesired)

      // debugging
      /*if (true) {
        topWords.foreach { wm =>
          val tf = bm25Tf(counts(wm.word)._1, docLength)
          println(f"${wm.word}: ($tf%.2f * ${idfModel.transform(wm.word)}%.4f) = ${tf * idfModel.transform(wm.word)}%.4f ")
        }
      }*/

      (topWords, docLength)
    }
  }

  /** Principal Component Analysis of weighted word vectors.  Returns principal axes; see principalAxes ScalaDoc. */
  def text2PcaVecs(topWords: Seq[WordMass], nComponents: Int, idfVec: Option[Vec]): Seq[Vec] =
    utils.principalAxes(topWords.map(_.scaledVec), nComponents, mbOrientationVec = idfVec)

  /** Distance metric used in `text2KMeansVecs`--must be scale invariant. */
  def kmd(v0: Vec, v1: Vec): Double = 1.0 - (v0 cosine v1)

  // `kmd` tolerance for two vectors to be considered equivalent (`cosine` appears to have error around 1e-15)
  val KMD_TOL: Double = 1e-8

  /**
    * Hard to believe that there's not a common, open source implementation of k-means in Scava [sic], or at
    * least there doesn't appear to be.  Regardless, what we implement here is *weighted* k-means clustering.
    *
    * After implementing `desiredFracWords` which cuts down the number of words making it into these clustering
    * algorithms, these clusters don't appear to be any better than principal component clusters/axes--even
    * though they are properly directional while PCs are adirectional--at predicting high TF-IDF scores (see
    * kwsSimilarities.xlsx).
    */
  def text2KMeansVecs(topWords: Seq[WordMass], k: Int): (Seq[Vec], Double) = {

    if (k == 0) (Seq.empty[Vec], Double.NaN)
    else if (k >= topWords.size) (topWords.map(_.scaledVec), Double.NaN)
    else {

      /** Weighted k-means++ initialization: https://en.wikipedia.org/wiki/K-means%2B%2B */
      @tailrec
      def init(topWords: IndexedSeq[WordMass], k: Int, centers: Seq[Vec] = Seq.empty[Vec]):
                                                            Seq[Vec] = if (k == 0) centers else {
        init(topWords, k - 1, {
          // incorporate weighting even during initialization, points that have already been chosen as centers
          // will receive weights of 0
          val weightedDists = if (centers.isEmpty) topWords.map(_.mass) else {
            topWords map { wm =>
              val x = centers.map(kmd(_, wm.scaledVec)).min // distance to closest center
              if (x < KMD_TOL) 0.0 else wm.mass * math.pow(x, 2)
            }
          }

          // randomly choose a value between 0 and weightedDists.sum to correspond to the next chosen center
          // TODO: remove stochastic and just use highest mass-weighted distance in each step?
          val desiredSum = weightedDists.sum * Random.nextDouble

          /** Returns the size of `wgts` after the cumulative sum of its elements reaches `desiredSum`. */
          @tailrec
          def argTakeWhileSum(wgts: Seq[Double], s: Double = 0.0): Int =
            if (s > desiredSum || wgts.isEmpty) wgts.size else argTakeWhileSum(wgts.tail, s + wgts.head)

          // convert from remaining number of elements to an index of the stochastically chosen element
          val i = weightedDists.size - argTakeWhileSum(weightedDists) - 1
          centers :+ topWords(math.max(0, math.min(topWords.size - 1, i))).scaledVec.l2Normalize
        })
      }

      val initCenters = init(topWords.toIndexedSeq, k)

      /** nearest centers to each `topWords` vector. */
      def nearestCenters(centers: Seq[Vec]): Seq[(WordMass, Int)] = topWords map {
        wm => (wm, centers.map(kmd(_, wm.scaledVec)).zipWithIndex.minBy(_._1)._2)
      }

      /** The typical k-means algorithm. */
      @tailrec
      def step(centers: IndexedSeq[Vec]): Seq[Vec] = {

        val nrstCs = nearestCenters(centers)

        // find center of mass for each cluster given `nearestCenters`
        val nextCenters = centers.indices.map { i =>
          // https://stackoverflow.com/questions/25066863/fold-collection-with-none-as-start-value
          nrstCs.filter(_._2 == i).foldLeft(None: Option[Vec]) { case (agg, (wm, _)) =>
            agg match {
              case None => Some(wm.scaledVec)
              case Some(_) => agg.map(_ + wm.scaledVec)
            }
          }.getOrElse(centers(i)) // replace any Nones w/ previous step's cluster center for this `i`
        }

        // if none of the centers moved then we've converged
        if (centers.view.zip(nextCenters).forall { case (c0, c1) => kmd(c0, c1) < KMD_TOL }) centers
        else step(nextCenters)
      }

      val unorderedCenters = step(initCenters.toIndexedSeq)
      val nrstCs = nearestCenters(unorderedCenters)

      // sort centers in decreasing order of *total* mass (incl. mass from other clusters' points?)
      val orderedCenters = unorderedCenters.zipWithIndex.sortBy[Double] { case (cvec, i) =>
        nrstCs.filter(_._2 == i).foldLeft(0.toDouble) {
          case (agg, (wm, _)) => agg + wm.mass
        }
      }(Ordering.Double.reverse)
        .map(_._1)

      // cluster utility/variance/loss (must use `unorderedCenters` here b/c `nrstCs` were calculated w/ their indices)
      val loss = nrstCs.foldLeft(0.toDouble) { case (agg, (wm, i)) =>
        agg + math.pow(kmd(unorderedCenters(i), wm.scaledVec), 2)
      }

      logger.debug(f"K-means loss $loss%.5f for document with ${topWords.size} top words")
      (orderedCenters, loss)
    }
  }
}
