/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import java.util.Locale

import akka.stream.Materializer
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.models.Representation.{Vec, VecEnum, VecFunctions}
import com.hamstoo.models.{MSearchable, RSearchable, Representation}
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService => VecSvc}
import com.hamstoo.stream._
import com.hamstoo.stream.dataset.{QueryResult, RepredMarks, ReprsPair}
import com.hamstoo.utils.{DurationMils, ExtendedDouble, TimeStamp, parse}
import org.slf4j.LoggerFactory
import play.api.Logger

import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
  * Search relevance scores.  This class uses "raw" to refer to the opposite of "semantic" because I'm not sure
  * if "syntactic" is really the right word either.
  * See also: https://en.wikipedia.org/wiki/Semantic_similarity
  *
  * @param uraw  User-content raw (non-semantic) score.  Includes MongoDB Text Index search score.
  * @param usem  User-content vector similarity (cosine distance).
  * @param rraw  Representation raw (non-semantic) score.  Includes MongoDB Text Index search score.
  * @param rsem  Representation vector similarity (cosine distance).
  */
case class SearchRelevance(uraw: Double, usem: Double, rraw: Double, rsem: Double) {
  def sum: Double = uraw + usem + rraw + rsem
}

/**
  * Define the (default) implementation of this facet.
  * @param rawQuery     Only used for extracting (and boosting) full phrases in search ordering.
  * @param repredMarks  A stream of a user's marks paired with their representations.
  * @param query2Vecs   Semantic word vectors for each query term.
  */
@com.google.inject.Singleton
class SearchResults @Inject()(@Named(Query.name) rawQuery: Query.typ,
                              @Named(Query2VecsOptional.name) query2Vecs: Query2VecsType,
                              repredMarks: RepredMarks,
                              logLevel: LogLevelOptional.typ)
                             (implicit mat: Materializer,
                              idfModel: IDFModel)
    extends DataStream[SearchResults.typ] {

  // can only obtain an EC from an ActorMaterializer via `.system`, not from a plain old Materializer
  //implicit val ec: ExecutionContext = materializer.system.dispatcher

  import SearchResults._

  // set logging level for this SearchResults *instance* (change prefix w/ "I" to prevent modifying the other logger)
  val loggerI: Logger = {
    val logback = LoggerFactory.getLogger("I" + classOf[SearchResults].getName).asInstanceOf[LogbackLogger]
    logLevel.filter(_ != logback.getLevel).foreach { lv => logback.setLevel(lv); logback.debug(s"Overriding log level to: $lv") }
    new Logger(logback)
  }

  // SearchResults can end up being out of order per the mapAsyncUnordered below so we take a guess at by how much
  // here and if we lose any along the way it's not the end of the world (note that the degree to which they're
  // out of order has nothing to do with either the clock.interval or the preloadInterval of the dependency
  // streams as both of those values are irrelevant at this point in the stream graph)
  override val joinExpiration: DurationMils = (365 days).toMillis

  // get uniquified `cleanedQSeq` and (future) vectors for all terms in search query `fsearchTermVecs`
  private lazy val (cleanedQuery, fsearchTermVecs) = query2Vecs
  private lazy val cleanedQSeq = cleanedQuery.map(_._1)

  // repredMarks will arrive according to time, but search results don't need to be ordered after here because we
  // re-order them later anyway, so just forward them on to the next downstream consumer as soon as they're complete
  override val in: SourceType[typ] = repredMarks().mapAsyncUnordered(16) { dat: Datum[(MSearchable, ReprsPair)] =>

    // unpack the pair datum
    val (mark, ReprsPair(siteReprs, userReprs)) = dat.value

    // the `marks` collection includes the users own input (assuming the user chose to provide any input
    // in the first place) so it should be weighted pretty high if a word match is found, the fuzzy reasoning
    // behind why it is squared is because the representations collection is also incorporated twice (once
    // for database search score and again with vector cosine similarity), the silly 3.5 was chosen in order
    // to get a non-link mark (one w/out a repr) up near the top of the search results
    val mscore: Double = mark.score.getOrElse(0.0) /** MongoRepresentationDao.CONTENT_WGT*/ / cleanedQuery.length

    // generate a single search result
    val fut = for(searchTermVecs <- fsearchTermVecs) yield {

      val startTime: TimeStamp = System.currentTimeMillis()

      // compute scores aggregated across all search terms
      val (rscore, rsim, rText, rTermText) = searchTerms2Scores("R", mark.id, siteReprs, searchTermVecs)
      val (uscore, usim, uText, uTermText) = searchTerms2Scores("U", mark.id, userReprs, searchTermVecs, nWordsMult = 100)

      // semantic relevances
      val rsem = math.exp(rsim.getOrElse(0.0))
      val usem = math.exp(usim.getOrElse(0.0))

      // raw (syntactic?) relevances; coalesce0 means that we defer to mscore for isNaN'ness below if uscore is NaN
      val uraw0 = math.max(uscore, 0.0).coalesce0 + math.max(mscore, 0.0)
      val rraw0 = math.max(rscore, 0.0)

      val previewer = Previewer(rawQuery, cleanedQuery, mark.id)
      val utext = parse(mark.mark.comment.getOrElse(""))
      val rtext = parse(siteReprs.find(_.mbR.isDefined).flatMap(_.mbR).fold("")(_.doctext))

      val disablePreviewText = false
      val disabledPreview = (0, Seq.empty[(Double, String)])
      val t0: TimeStamp = System.currentTimeMillis()
      val (uPhraseBoost, uPreview) = if (disablePreviewText) disabledPreview else previewer(uraw0, utext)
      val (rPhraseBoost, rPreview) = if (disablePreviewText) disabledPreview else previewer(rraw0, rtext)
      val t1: TimeStamp = System.currentTimeMillis()
      logger.debug(f"Previewer[total] for ${mark.id} in ${t1 - t0} ms")

      val uraw = uraw0 + uPhraseBoost
      val rraw = rraw0 + rPhraseBoost
      val preview: String = (uPreview ++ rPreview).sortBy(-_._1).take(N_SPANS).map(_._2).mkString("<br>")

      // aggregated
      val isdefBonus = Seq(rscore, mscore, uscore).count(_ > 1e-10)
      val rAggregate = rsem + rraw
      val mAggregate = usem + uraw
      loggerI.trace(f"  (\u001b[2m${mark.id}\u001b[0m) scores: agg(r/m)=$rAggregate%.2f/$mAggregate%.2f text-search(r/m/u)=$rscore%.2f/$mscore%.2f/$uscore%.2f similarity(r/u)=${rsim.getOrElse(Double.NaN)}%.2f/${usim.getOrElse(Double.NaN)}%.2f")

      // divy up the relevance into named buckets
      val mbRelevance = (rAggregate.isNaN, mAggregate.isNaN, mark.score.isDefined) match {
        case (true, true, true) => // this first case shouldn't ever really happen
          Some(SearchRelevance(mscore + isdefBonus, 0, 0, 0))

        case (true, false, _) => // this second case will occur for non-URL marks
          Some(SearchRelevance(      uraw * 1.6 + isdefBonus, usem * 1.6, 0, 0))

        case (false, true, _) => // this case will occur for old-school bookmarks without any user content
          Some(SearchRelevance(0, 0, rraw * 1.4 + isdefBonus, rsem * 1.4))

        case (false, false, _) => // this case should fire for most marks--those with URLs

          // maybe want to do max(0, cosine)?  or incorporate antonyms and compute cosine(query-antonyms)?
          // because antonyms may be highly correlated with their opposites given similar surrounding words
          Some(SearchRelevance(uraw + Seq(mscore, uscore).count(_ > 1e-10), usem,
                               rraw + Seq(rscore        ).count(_ > 1e-10), rsem))

        case (_, _, false) => None
      }

      val aggregateScore = mbRelevance.map(_.sum)

      // generate text and return values
      val mbPv = (rAggregate.isNaN, mAggregate.isNaN, mark.score.isDefined) match {
        case (true, true, true) => // this first case shouldn't ever really happen
          val sc = mark.score.getOrElse(Double.NaN)
          val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b> (bonus=$isdefBonus), " +
            f"Raw marks database search score: <b>$sc%.2f</b> (phrase=$uPhraseBoost)"
          Some(s"$scoreText<br>")

        case (true, false, _) => // this second case will occur for non-URL marks
          val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b> (bonus=$isdefBonus), " +
            f"User content similarity: <b>exp(${usim.getOrElse(Double.NaN)}%.2f)</b>, " +
            f"Database search scores: M/U=<b>$mscore%.2f</b>/<b>$uscore%.2f</b>=<b>${mscore/uscore}%.2f</b> (phrase=$uPhraseBoost)"
          Some(s"$scoreText<br>U-similarities: $uTermText&nbsp; $uText<br>")

        case (false, true, _) => // this case will occur for old-school bookmarks without any user content
          val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b> (bonus=$isdefBonus), " +
            f"URL content similarity: <b>exp(${rsim.getOrElse(Double.NaN)}%.2f)</b>, " +
            f"Database search scores: R=<b>$rscore%.2f</b> (phrase=$rPhraseBoost)"
          Some(s"$scoreText<br>R-similarities: $rTermText&nbsp; $rText<br>")

        case (false, false, _) => // this case should fire for most marks--those with URLs
          val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b> (bonus=$isdefBonus), " +
            f"Similarities: R=<b>exp(${rsim.getOrElse(Double.NaN)}%.2f)</b>, U=<b>exp(${usim.getOrElse(Double.NaN)}%.2f)</b>, " +
            f"Database search scores: R=<b>$rscore%.2f</b>, M/U=<b>$mscore%.2f</b>/<b>$uscore%.2f</b>=<b>${mscore/uscore}%.2f</b> (phrase=$rPhraseBoost & $uPhraseBoost)"
          Some(s"$scoreText<br>" + s"R-similarities: $rTermText&nbsp; $rText<br>" +
                                   s"U-similarities: $uTermText&nbsp; $uText<br>")

        case (_, _, false) => None

      }

      val endTime: TimeStamp = System.currentTimeMillis()
      val elapsed = (endTime - startTime) / 1e3
      if (elapsed > 0.1)
        loggerI.debug(f"\u001b[35m${mark.id}\u001b[0m: query= '$rawQuery', subj='${mark.mark.subj}, textLen=${utext.length + rtext.length}' in $elapsed%.3f seconds")

      mbPv.flatMap { pv =>

        // remove results with no preview/syntactic matches (unless score is really high), requires that all
        // fields (e.g. comments, highlights, inline notes) are being covered by MongoDB text search, which they
        // should be via user-content reprs' doctext
        // TODO: "unless score is really high"--and make this dependent on 'sem' facet arg
        val mbPr = preview match {
          case pr if pr.nonEmpty || disablePreviewText =>
            logger.debug(s"Including mark ${mark.id} in search results; has preview text")
            Some(pr)
          case _ if (uraw.coalesce0 + rraw.coalesce0) < 1e-8 =>
            logger.debug(s"Excluding mark ${mark.id} from search results; no preview text")
            None
          case _ =>
            logger.debug(s"Including mark ${mark.id} in search results; has database text matches")
            def withDots(s: String): String = if (s.length < PREVIEW_LENGTH) s else s"${s.take(PREVIEW_LENGTH)}..."
            Some(Seq(rtext, utext).filter(_.nonEmpty).map(SearchResults.encode).map(withDots).mkString("<br>"))
        }

        mbPr.map { pr => (mark, (if (loggerI.isDebugEnabled) pv else "") + pr, mbRelevance) }
      }
    }

    fut.map { _.map(dat.withValue) }

  }.mapConcat(_.to[immutable.Iterable]) // a.k.a. flatten
    .asInstanceOf[SourceType[typ]] // see "BIG NOTE" on JoinWithable

  /**
    * Convert a list of reprs, one for each search term, into a weighted average MongoDB Text Index search score
    * and a semantic score based on vector cosine similarity.
    */
  def searchTerms2Scores(rOrU: String,
                         mId: String,
                         searchTermReprs: Seq[QueryResult],
                         searchTermVecs: Seq[VecSvc.WordMass],
                         nWordsMult: Int = 1): (Double, Option[Double], String, String) = {

    // there must be at least one search term with a representation
    val mbR: Option[RSearchable] = searchTermReprs.find(_.mbR.isDefined).flatMap(_.mbR)

    // must use `Option.empty[Double]` here, not `None`, else the following compiler error occurs:
    // "type mismatch; found: similarity.type (with underlying type Option[Double])  required: None.type"
    mbR.fold(Double.NaN, Option.empty[Double], "", "") { repr: RSearchable =>

      // the repr is the same for all elements of the list per `r.get(reprId)` above so we can just look at head
      val nWords = repr.nWords.getOrElse(0.toLong) * nWordsMult
      val docVecs: Map[String, Vec] = repr.vectors

      // (from the MongoDB Text Index documentation: "For each indexed field in the document, MongoDB multiplies
      // the number of matches by the weight and sums the results. Using this sum, MongoDB then calculates the
      // score for the document." note that it just says "using this sum" not *how* it uses the sum)

      // normalize rscore for document length as longer documents are more likely to have matches, by definition,
      // (we used to do this by dividing by sqrt(reprText.length) which is similar to BM25 when its `b` parameter
      // is 1--we have `b` hard-coded to 0.5 in `bm25Tf`--and w/out the sqrt)
      import com.hamstoo.services.VectorEmbeddingsService.bm25Tf
      def bm25(qr: QueryResult): (Double, Double, Int) = {

        if (loggerI.isTraceEnabled) {
          val owm = searchTermVecs.find(_.word == qr.qword) // same documentSimilarity calculation as below
          val mu = owm.map(wm => VecSvc.documentSimilarity(wm.scaledVec, docVecs.map(kv => VecEnum.withName(kv._1) -> kv._2))).getOrElse(Double.NaN)
          loggerI.trace(f"  (\u001b[2m${mId}\u001b[0m) $rOrU-sim(${qr.qword}): idf=${idfModel.transform(qr.qword)}%.2f bm25=${bm25Tf(qr.dbScore, nWords)}%.2f sim=$mu%.2f")
        }

        (idfModel.transform(qr.qword), bm25Tf(qr.dbScore, nWords), qr.count)
      }
      // https://en.wikipedia.org/wiki/Okapi_BM25
      val searchTermScores = searchTermReprs.map(bm25)

      // arithmetic mean (no penalty for not matching all the query words; geo mean imposes about a 12% penalty
      // for missing 1 out of 3 query words and 15% for missing 2 out of 3)
      val score0 = searchTermScores.map(x => x._1 * x._3 * x._2).sum / searchTermScores.map(x => x._1).sum

      // geometric mean, computed with logs (using 0.1 here instead of 1.0 imposes a larger penalty on documents
      // that don't match all the query words; e.g. if there are 3 query words, a document that has 1 missing
      // will have about a 25% lower geo mean with 0.1 than with 1.0, and 2 missing out of 3 will be 50% lower)
      val score = math.exp(searchTermScores.map(x => x._1 * x._3 * math.log(x._2 + 0.1)).sum /
                           searchTermScores.map(x => x._1 * x._3                       ).sum) - 0.1

      // geometic mean, computed w/out logs (unstable)
      val score2 = math.pow(searchTermScores.map(x => math.pow(x._2 + 1, x._1 * x._3)).product,
                      1.0 / searchTermScores.map(x =>                    x._1 * x._3 ).sum) - 1

      // debugging
      // calculate cosine similarities for each of the search terms and sum them (no need to use geometric mean
      // here because every document is guaranteed to have some level of semantic similarity to each query term
      // regardless of whether the query term actually appears in the doc)
      var extraTermText = ""
      val similarity: Option[Double] = if (searchTermVecs.isEmpty || docVecs.isEmpty) None else {
        extraTermText = "Terms:"

        // there will be one element in this collection for each search term (this collection cannot be a set)
        val similarities = for { wm <- searchTermVecs } yield {
          val mu = VecSvc.documentSimilarity(wm.scaledVec, docVecs.map(kv => VecEnum.withName(kv._1) -> kv._2))
          extraTermText += f" ${wm.word}=<b>$mu%.2f</b>" // debugging
          mu
        }

        // idfs could also be backed out from WordMass objects (i.e. mass / tf)
        val idfs = searchTermVecs.map(wm => idfModel.transform(wm.word))
        val weightedSum = similarities.zip(idfs).map { case (a, b) => a * b }.sum
        Some(weightedSum / idfs.sum)
      }

      loggerI.trace(f"  (\u001b[2m${mId}\u001b[0m) $rOrU-sim: wsim=${similarity.getOrElse(Double.NaN)}%.2f wscore=$score%.2f (s0=$score0%.2f s2=$score2%.2f nWords=$nWords nScores=${searchTermScores.length})")

      // debugging
      var extraText = ""
      if (searchTermVecs.nonEmpty) {
        extraText = "Docs:"
        docVecs.map { case (vt, vec) =>
          vt -> searchTermVecs.map { wm =>
            val cos = wm.scaledVec cosine vec
            if (vt./*toString.*/startsWith("PC")) math.max(cos, -0.95 * cos) else cos
          }.sum
        }.toSeq.sortBy(_._2)
          .foreach(kv => extraText += f" ${kv._1}=<b>${kv._2}%.2f</b>")
      }

      (score, similarity, extraText, extraTermText)
    }
  }
}

object SearchResults {

  type typ = (MSearchable, String, Option[SearchRelevance])

  val logger = Logger(getClass)

  // capital letter regular expression (TODO: https://github.com/Hamstoo/hamstoo/issues/68)
  val capitalRgx: Regex = s"[A-Z]".r.unanchored

  private val PREVIEW_LENGTH = 150
  private val N_SPANS = 3
  private val MIN_PREFIX_LENGTH = 4

  // function for html tags encoding (use StringEscapeUtils.escapeHtml4 here instead?)
  def encode(s: String): String = s.replace("<", "&#60;").replace(">", "&#62;").trim

  /**
    * A functor to process text previews and perform phrase search, which MongoDB cannot.
    * @param rawQuery       Raw query (includes double quotes) as processed by hamstoo project's ProcessedSearchString.
    * @param cleanedQuery0  Individual query words with their counts in the query string.  Also lowercase'ized, and
    *                       cleaned of some punctuation as performed by VectorEmbeddingsService.query2Vecs.
    */
  case class Previewer(rawQuery: String, cleanedQuery0: Seq[(String, Int)], markId: String) {

    // necessary because of `applyOrElse` below
    private val cleanedQuery = cleanedQuery0.toIndexedSeq

    // consecutive pairs of double quotes demarcate phrases
    val cleanedPhrasesSeq: Seq[String] = rawQuery.split("\\\"").zipWithIndex
                                           .collect { case (phrase, i) if i % 2 == 1 => parse(phrase) }

    /**
      * Generates the HTML preview of the text with emboldened query words.
      * @param dbSearchScore  Indicator of whether there _should_ be matching words to find.
      * @param rawText0       Raw text which should already have been `utils.parsed`ed.
      */
    def apply(dbSearchScore: Double, rawText0: String): (Int, Seq[(Double, String)]) = {
      if (rawText0.isEmpty) (0, Seq.empty[(Double, String)]) else {

        val rawText = rawText0.take(50000)
        var startTime = System.currentTimeMillis

        val encText = encode(rawText)
        assert(encText.length >= rawText.trim.length)

        val lowText = encText.toLowerCase(Locale.ENGLISH) // has already been `utils.parse`ed
        val query = (cleanedQuery.map(_._1) ++ cleanedPhrasesSeq).map(_.toLowerCase(Locale.ENGLISH).replace("\"", ""))

        val qcounts = mutable.ArrayBuffer.fill[Int](query.size)(0) // for boosting search scores for phrases
        val tcountsUncmp = mutable.ArrayBuffer.fill[Double](lowText.length)(0) // to locate dense term regions in text

        var endTime = System.currentTimeMillis
        logger.trace(f"Previewer[a] $markId in ${endTime - startTime} ms") // 15 ms
        startTime = System.currentTimeMillis

        val preproc: IndexedSeq[(IndexedSeq[(Char, Int)], Int)] = query.map(_.zipWithIndex).zipWithIndex
        val lenUncmp = lowText.length

        // for each substring of `text` that follows whitespace, look for query words/phrases/terms
        // TODO: these first 2 variation lines are NOT THREADSAFE (but how _much_ does it really matter in this case?)
        preproc.par.foreach { case (term, j) => // 1.399 or 1.332 seconds
        //lowText.indices.par.foreach { i => // 1.482 seconds
          for(i <- lowText.indices/*; (term, j) <- preproc*/) yield { // 1.583 seconds (for 15 marks total search time)

            if ((i == 0 || lowText(i - 1).isWhitespace) &&
                term.forall { case (ch, k) => i + k < lenUncmp && ch == lowText(i + k) }) {
              qcounts(j) += 1

              // compute term score (for sorting purposes) as sqrt(term.length) but penalize capital letters a little
              val encTerm = encText.slice(i, i + term.length)
              val nCaps = capitalRgx.findAllIn(encTerm).length

              // this is effectively dividing by sqrt(term.length) twice, once to convert from a whole-word score
              // to a char score and then again to counter the x^2 kernel below (consider this: if nCaps is 0, then
              // this reduces to 1/sqrt for each char)
              val charScore = math.sqrt(term.length.toDouble - 0.25 * nCaps) / term.length
              val nDuplicates = cleanedQuery.map(_._2).applyOrElse(j, (_: Int) => 1) // phrases won't have duplicates
              logger.trace(f"'${term.map(_._1).mkString("")}' at $i (nDuplicates=$nDuplicates, charScore=$charScore%.3f)")
              term.indices.foreach { k => tcountsUncmp(i + k) += nDuplicates * charScore }
            }
          }
        }

  // TODO: at this point we know there's a match, so we could return a Future from here on and so not have to wait

  // TODO: another thing we could do would be to only compute previews for the top 20 marks similar to not rendering them all

        endTime = System.currentTimeMillis
        logger.debug(f"Previewer[0] $markId (${rawText.length}) in ${endTime - startTime} ms") // 14 ms
        startTime = System.currentTimeMillis

        // compress tcounts b/c if you don't things are realllllyyy slllloooowwwwww (this value has quadratic effect
        // and so 30 will reduce a 20000-char string from 1 second down to around 1 millisecond)
        val COMPRESSION = 30
        //val tcountsCmp = tcountsUncmp.zipWithIndex.groupBy(_._2 / COMPRESSION).toSeq.sortBy(_._1).map(_._2.map(_._1).mean) // 25 ms
        import math.{abs, max, min, pow}
        val tcountsCmp = (0 until lenUncmp by COMPRESSION).map { i0: Int => // 3 ms
            val i1 = min(lenUncmp, i0 + COMPRESSION)
            tcountsUncmp.slice(i0, i1).mean
          }

        endTime = System.currentTimeMillis
        logger.trace(f"Previewer[b] $markId (${rawText.length}) in ${endTime - startTime} ms") // 3 ms
        startTime = System.currentTimeMillis

        logger.trace(s"tcountsCmp: ${tcountsCmp.map(x => f"$x%.2f")}")
        val nMatchedPhrases = qcounts.takeRight(cleanedPhrasesSeq.size).sum

        // smooth tcounts using an upside down parabola
        val (kernel, kernelUnscaled, xmid, prvLenCmp) = {
          val prvLenCmp0: Int = PREVIEW_LENGTH / COMPRESSION + 2 // add 2 so that we can remove leading/trailing 0s
          val prvLenEven = prvLenCmp0 + (prvLenCmp0 % 2) // make it even to make the logic easier below
          val xmid0 = prvLenEven / 2
          val ymax = pow(xmid0, 2)
          val krnUnscaled0 = (0 to prvLenEven).map { x => 1 - pow(abs(x - xmid0), 2) / ymax } // e.g. length = 9
          val krn0 = krnUnscaled0 / krnUnscaled0.sum
          assert((krn0.head ~= 0.0) && (krn0.last ~= 0.0) && (krn0.sum ~= 1.0) && (krnUnscaled0(xmid0) ~= 1.0))

          // remove leading/trailing 0s to speed things up
          (krn0.tail.init, krnUnscaled0.tail.init, xmid0 - 1, prvLenCmp0 - 2)
        }

        endTime = System.currentTimeMillis
        logger.trace(s"kernel: ${kernel.map(x => f"$x%.2f")}")
        logger.trace(f"Previewer[c] $markId in ${endTime - startTime} ms") // 1 ms
        startTime = System.currentTimeMillis

        // this loop takes forever w/out compression, and the par/seq helps a bit too
        val lenCmp = tcountsCmp.length
        val smoothedImmutableCmp = tcountsCmp.indices.par.map { i =>
          val begin = max(0, i - xmid)
          val end = min(lenCmp, i + xmid + 1) // add 1 b/c ends are always exclusive

          // truncate the kernel if towards the beginning or end of the text
          val bTrunc = if (         i     > xmid) 0 else xmid -           i
          val eTrunc = if (lenCmp - i - 1 > xmid) 0 else xmid - (lenCmp - i - 1) // would be nice if Scala supported negative i
          assert(end - begin + bTrunc + eTrunc == kernel.length)

          val kernel_i = kernelUnscaled.slice(bTrunc, kernelUnscaled.length - eTrunc) // if truncs are 0, same as `kernel`
          tcountsCmp.slice(begin, end) dot (kernel_i / kernel_i.sum)
        }.seq

        endTime = System.currentTimeMillis
        logger.debug(f"Previewer[1] $markId (${rawText.length}) in ${endTime - startTime} ms") // 25 ms
        startTime = System.currentTimeMillis
        val smoothedCmp = mutable.ArrayBuffer(smoothedImmutableCmp: _*)

        // this mutable is used to prevent duplicates but still allow stopping when enough have been found
        val previewTexts = mutable.ArrayBuffer.empty[(Double, String)]

        // TODO: score earlier snippets higher?
        while(previewTexts.length < N_SPANS && {
          val amaxCmp = smoothedCmp.argmax
          amaxCmp > 0 || !(smoothedCmp.sum ~= 0) // either there aren't any words to bold, or we already exhausted them all
        }) {
          val amaxCmp = smoothedCmp.argmax
          val amax = amaxCmp * COMPRESSION
          val smoothedMax = smoothedCmp(amaxCmp)

          // create a preview snippet around amax as a midpoint
          val begin = max(0, amax - PREVIEW_LENGTH / 2)
          val end = min(encText.length, amax + PREVIEW_LENGTH / 2)
          val snippet = encText.slice(begin, end)
          val scounts = tcountsUncmp.slice(begin, end) // be sure to not use smoothed here, we need binary 0/nonzero values

          // erase this region of `smoothed` so that it is not selected in next N_SPANS loop iteration, but don't use
          // begin & end because any word with length > 1 will affect a larger range than just PREVIEW_LENGTH (so rather
          // than multiplying prvLenCmp by 1/2 we expand it a bit to 5/6--or a 1/3 increase on either end)
          // TODO: reduce other regions of smoothed that include the same query words as just found
          val plX = prvLenCmp * 5 / 6
          logger.trace(f"argmax (${COMPRESSION}x compressed) = $amaxCmp, max = $smoothedMax%.3f, plX = $plX")
          logger.trace(s"smoothedCmp: ${smoothedCmp.map(x => f"$x%.2f")}")
          (max(0, amaxCmp - plX) until min(smoothedCmp.length, amaxCmp + plX)).foreach { i => smoothedCmp.update(i, 0) }

          // this will embolden consecutive words, but not the spaces between them, which is kinda silly, but who cares
          var isOpen = false
          val emboldened = snippet.zip(scounts).map {
            case (ch, n) if n >  1e-8 && !isOpen => { isOpen = true ;  "<b>" } + ch // enbolden
            case (ch, n) if n <= 1e-8 &&  isOpen => { isOpen = false; "</b>" } + ch // debolden
            case (ch, _) => ch
          }.mkString("") + (if (isOpen) "</b>" else "")

          val ptext = (if (begin == 0) "" else "...") + emboldened.trim + (if (end == encText.length) "" else "...")

          // ignore duplicates
          if (previewTexts.forall(x => Representation.editSimilarity(x._2, ptext) < 0.8))
            previewTexts += smoothedMax -> ptext
        }

        endTime = System.currentTimeMillis
        logger.trace(f"Previewer[d] $markId in ${endTime - startTime} ms") // 1 ms

        // return number of matched phrases from first recursive iteration no matter what
        (nMatchedPhrases, if (previewTexts.nonEmpty) previewTexts else {

          // try harder to find something if there should be something to find (i.e. if dbSearchScore > 0)
          val prfxQuery = cleanedQuery.filter(_._1.length > MIN_PREFIX_LENGTH).map(kv => (kv._1.init, kv._2))
          if ((dbSearchScore ~= 0.0) || prfxQuery.isEmpty) previewTexts else {
            val recursivePreviewTexts = Previewer("", prfxQuery, markId+"-r")(dbSearchScore, rawText)._2
            if (recursivePreviewTexts.isEmpty) previewTexts else recursivePreviewTexts
          }
        })

        // IDEA: incremental runtime recompilation of dynamically typed languages as information is learned about runtime values (security?)
      }
    }
  }
}