/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.facet

import java.util.Locale

import akka.stream.Materializer
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.models.Representation.{Vec, VecEnum, VecFunctions}
import com.hamstoo.models.{Mark, RSearchable, Representation}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService => VecSvc}
import com.hamstoo.stream.Data.{Data, ExtendedData}
import com.hamstoo.stream._
import com.hamstoo.stream.dataset.{ReprQueryResult, RepredMarks, ReprsPair}
import com.hamstoo.utils.{ExtendedDouble, ExtendedTimeStamp, TimeStamp, memoryString, parse}
import org.slf4j.LoggerFactory
import play.api.Logger

import scala.annotation.tailrec
import scala.collection.{breakOut, mutable}
import scala.concurrent.Future
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
  override def toString: String = f"${getClass.getSimpleName}($uraw%.2f, $usem%.2f, $rraw%.2f, $rsem%.2f)"
}

/**
  * Define the (default) implementation of this facet.
  *
  * Note that the 2 @Named parameters use InjectId.name but do not use InjectId.typ as their types.  This is why
  * the 2 additional, non-optional StreamModule @Provides methods are needed.  This is perhaps confusing though, so
  * maybe SearchResults should just take Options as inputs and throw an exception if they are None, which is what
  * the @Provides methods already do anyway--one fewer degree of indirection.
  *
  * @param mbQuery2Vecs  Semantic word vectors for each query term.  Cannot be None: we used to have a separate
  *                      \@Provides method in StreamModule that would simply call mbQuery2Vecs.get, but it was getting
  *                      called even when SearchResults wasn't being used, so now we just call it inside this
  *                      class and suffer the "None.get exception" consequences if need be.
  * @param rawQuery      Only used for phrase search: extracting (and boosting) full phrases in results ordering.
  *                      `mbQuery2Vecs` is the primary way that SearchResults acquires its search terms.
  * @param anyVsAllArg   A value between 0 and 1 that determines how much weight is put on the addition of search
  *                      scores for each query term ("any") vs. the product of scores ("all").  0 corresponds to 100%
  *                      "any" while 1 to 100% "all."
  * @param repredMarks   A stream of a user's marks paired with their representations.
  */
@com.google.inject.Singleton
class SearchResults @Inject()(@Named(Query2Vecs.name) mbQuery2Vecs: Query2Vecs.typ,
                              rawQuery: QueryOptional,
                              anyVsAllArg: SearchResults.AnyVsAllArg,
                              repredMarks: RepredMarks,
                              logLevel: LogLevelOptional)
                             (implicit mat: Materializer,
                              idfModel: IDFModel)
    extends DataStream[SearchResults.typ] {

  // can only obtain an EC from an ActorMaterializer via `.system`, not from a plain old Materializer
  //implicit val ec: ExecutionContext = materializer.system.dispatcher

  import SearchResults._

  // set logging level for this SearchResults *instance* (change prefix w/ "I" to prevent modifying the other logger)
  val loggerI: Logger = {
    val logback = LoggerFactory.getLogger("I" + classOf[SearchResults].getName).asInstanceOf[LogbackLogger]
    logLevel.value.filter(_ != logback.getLevel).foreach { lv => logback.setLevel(lv); logback.debug(s"Overriding log level to: $lv") }
    new Logger(logback)
  }

  // for timing profiling
  private var constructionTime: Option[TimeStamp] = Some(System.currentTimeMillis)

  // get uniquified `cleanedQSeq` and (future) vectors for all terms in search query `fsearchTermVecs`
  private lazy val (cleanedQuery, fsearchTermVecs) = mbQuery2Vecs.get
  private lazy val cleanedQSeq = cleanedQuery.map(_._1)

  // repredMarks will arrive according to time, but search results don't need to be ordered after here because we
  // re-order them later anyway, so just forward them on to the next downstream consumer as soon as they're complete
  override val in: SourceType = repredMarks()
    .map { d => logger.debug(s"repredMarks.out: ${d.sourceTimeMax.tfmt}"); d }
    .mapAsync(4) { d: Data[RepredMarks.typ] =>
      Future.sequence {
        d.map { e: Datum[RepredMarks.typ] =>

          // unpack the pair datum
          val (mark, ReprsPair(siteReprs, userReprs)) = e.value

          // the `marks` collection includes the users own input (assuming the user chose to provide any input
          // in the first place) so it should be weighted pretty high if a word match is found, the fuzzy reasoning
          // behind why it is squared is because the representations collection is also incorporated twice (once
          // for database search score and again with vector cosine similarity), the silly 3.5 was chosen in order
          // to get a non-link mark (one w/out a repr) up near the top of the search results
          val mscore: Double = mark.score.getOrElse(0.0) /** MongoRepresentationDao.CONTENT_WGT*/ / cleanedQuery.length

          // generate a single search result
          fsearchTermVecs.map { searchTermVecs =>

            val startTime: TimeStamp = System.currentTimeMillis()
            constructionTime.foreach { t =>
              constructionTime = None
              logger.info(f"Time between construction and first incoming element: ${(startTime - t) / 1e3}%.3f seconds ($memoryString)")
            }

            // compute scores aggregated across all search terms
            val ava: Double = anyVsAllArg.value
            val rst: ScoresAndText = searchTerms2Scores("R", mark.id, ava, siteReprs, searchTermVecs)
            val ust: ScoresAndText = searchTerms2Scores("U", mark.id, ava, userReprs, searchTermVecs, nWordsMult = 100)

            // raw (syntactic?) relevances; coalesce0 means that we defer to mscore for isNaN'ness below if uscore is NaN
            val dbscore = Seq(ust.raw, rst.raw).map(math.max(_, 0.0).coalesce0).sum

            val previewer = Previewer(rawQuery.value, cleanedQuery, mark.id)
            val utext = parse(mark.mark.comment.getOrElse(""))
            val rtext = parse(siteReprs.find(_.mbR.isDefined).flatMap(_.mbR).fold("")(_.doctext))
            val urtext = utext + " " * PREVIEW_LENGTH + rtext

            val t0: TimeStamp = System.currentTimeMillis()
            val (urPhraseBoost, urPreview) = if (WHICH_PREVIEW_TEXT ==  1) previewer(dbscore, urtext)
                                        else if (WHICH_PREVIEW_TEXT == -1) previewer.old(dbscore, urtext) else DISABLED_PREVIEW
            val (uPhraseBoost, rPhraseBoost) = (urPhraseBoost * 0.5, urPhraseBoost * 0.5)
            val t1: TimeStamp = System.currentTimeMillis()
            logger.debug(f"Previewer[total] for ${mark.id} in ${t1 - t0} ms ($memoryString)")

            val uraw = ust.raw + uPhraseBoost
            val rraw = rst.raw + rPhraseBoost
            val preview: String = urPreview.sortBy(-_._1).take(N_SPANS).map(_._2).mkString("<br>")

            // aggregated
            val rAggregate = rst.similarity + rraw
            val mAggregate = ust.similarity + uraw
            loggerI.trace(f"  (\u001b[2m${mark.id}\u001b[0m) scores: agg(r/m)=$rAggregate%.2f/$mAggregate%.2f text-search(r/m/u)=${rst.raw}%.2f/$mscore%.2f/${ust.raw}%.2f similarity(r/u)=${rst.similarity}%.2f/${ust.similarity}%.2f")

            // divy up the relevance into named buckets
            val mbRelevance = (rAggregate.isNaN, mAggregate.isNaN, mark.score.isDefined) match {
              case (true, true, true) => // this first case shouldn't ever really happen
                Some(SearchRelevance(mscore, 0, 0, 0))

              case (true, false, _) => // this second case will occur for non-URL marks
                Some(SearchRelevance(      uraw * 1.6, ust.similarity * 1.6, 0, 0))

              case (false, true, _) => // this case will occur for old-school bookmarks without any user content
                Some(SearchRelevance(0, 0, rraw * 1.4, rst.similarity * 1.4))

              case (false, false, _) => // this case should fire for most marks--those with URLs

                // maybe want to do max(0, cosine)?  or incorporate antonyms and compute cosine(query-antonyms)?
                // because antonyms may be highly correlated with their opposites given similar surrounding words
                Some(SearchRelevance(uraw, ust.similarity, rraw, rst.similarity))

              case (_, _, false) => None
            }
            mbRelevance.foreach(rl => loggerI.trace(f"  (\u001b[2m${mark.id}\u001b[0m) $rl"))
            val relTxt = mbRelevance.fold("")(rl => f" (=${rl.uraw}%.1f+${rl.usem}%.1f+${rl.rraw}%.1f+${rl.rsem}%.1f)")

            val aggregateScore = mbRelevance.map(_.sum)
            val usrContentRatio = if (ust.raw ~= 0) Double.PositiveInfinity else mscore / ust.raw

            // generate text and return values
            val mbPv = (rAggregate.isNaN, mAggregate.isNaN, mark.score.isDefined) match {
              case (true, true, true) => // this first case shouldn't ever really happen
                val sc = mark.score.getOrElse(Double.NaN)
                val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b>$relTxt, " +
                  f"Raw marks database search score: <b>$sc%.2f</b> (phrase=$uPhraseBoost)"
                Some(s"$scoreText<br>")

              case (true, false, _) => // this second case will occur for non-URL marks
                val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b>$relTxt, " +
                  f"User content similarity: <b>${ust.similarity}%.2f</b>, " +
                  f"Database search scores: M/U=<b>$mscore%.2f</b>/<b>${ust.raw}%.2f</b>=<b>$usrContentRatio%.2f</b> (phrase=$uPhraseBoost)"
                Some(s"$scoreText<br>U-similarities: ${ust.termText}&nbsp; ${ust.text}<br>")

              case (false, true, _) => // this case will occur for old-school bookmarks without any user content
                val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b>$relTxt, " +
                  f"URL content similarity: <b>${rst.similarity}%.2f</b>, " +
                  f"Database search scores: R=<b>${rst.raw}%.2f</b> (phrase=$rPhraseBoost)"
                Some(s"$scoreText<br>R-similarities: ${rst.termText}&nbsp; ${rst.text}<br>")

              case (false, false, _) => // this case should fire for most marks--those with URLs
                val scoreText = f"Aggregate score: <b>${aggregateScore.get}%.2f</b>$relTxt, " +
                  f"Similarities: R=<b>${rst.similarity}%.2f</b>, U=<b>${ust.similarity}%.2f</b>, " +
                  f"Database search scores: R=<b>${rst.raw}%.2f</b>, M/U=<b>$mscore%.2f</b>/<b>${ust.raw}%.2f</b>=<b>$usrContentRatio%.2f</b> (phrase=$rPhraseBoost & $uPhraseBoost)"
                Some(s"$scoreText<br>" + s"R-similarities: ${rst.termText}&nbsp; ${rst.text}<br>" +
                                         s"U-similarities: ${ust.termText}&nbsp; ${ust.text}<br>")

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

                case pr if pr.nonEmpty || WHICH_PREVIEW_TEXT == 0 =>
                  logger.debug(s"Including mark ${mark.id} in search results; has preview text")
                  Some(pr)

                // mscore is really only used here to prevent FacetTests from returning all 0s
                case _ if ((uraw + mscore).coalesce0 + rraw.coalesce0) < 1e-8 =>
                  logger.debug(s"Excluding mark ${mark.id} from search results; no preview text")
                  None

                case _ =>
                  logger.debug(s"Including mark ${mark.id} in search results; has database text matches")
                  def withDots(s: String): String = if (s.length < PREVIEW_LENGTH) s else s"${s.take(PREVIEW_LENGTH)}..."
                  Some(Seq(rtext, utext).filter(_.nonEmpty).map(SearchResults.encode).map(withDots).mkString("<br>"))
              }

              mbPr.map { pr => (mark, (if (loggerI.isDebugEnabled) pv else "") + pr, mbRelevance) }

            }.map(e.withValue)

          } // fsearchTermVecs.map
        } // d.map
      } // Future.sequence

    }.map(_.flatten) // remove Nones
      .asInstanceOf[SourceType] // see "BIG NOTE" on JoinWithable
      .map { d => logger.debug(s"${d.sourceTimeMax.tfmt} (sz=${d.size})"); d }

  case class ScoresAndText(raw: Double, similarity: Double, text: String, termText: String)

  /**
    * Convert a list of reprs, one for each search term, into a weighted average MongoDB Text Index search score
    * and a semantic score based on vector cosine similarity.
    */
  def searchTerms2Scores(rOrU: String,
                         mId: String,
                         anyVsAllArg: Double,
                         searchTermReprs: Seq[ReprQueryResult],
                         searchTermVecs: Seq[VecSvc.WordMass],
                         nWordsMult: Int = 1): ScoresAndText = {

    // there must be at least one search term with a representation
    val mbR: Option[RSearchable] = searchTermReprs.find(_.mbR.isDefined).flatMap(_.mbR)

    // must use `Option.empty[Double]` here, not `None`, else the following compiler error occurs:
    // "type mismatch; found: similarity.type (with underlying type Option[Double])  required: None.type"
    mbR.fold(ScoresAndText(0.0, 0.0, "", "")) { repr: RSearchable =>

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
      case class Score(idf: Double, b: Double, n: Int)
      def bm25(qr: ReprQueryResult): Score = {

        if (loggerI.isTraceEnabled) {
          val owm = searchTermVecs.find(_.word == qr.qword) // same documentSimilarity calculation as below
          val mu = owm.map(wm => VecSvc.documentSimilarity(wm.scaledVec, docVecs.map(kv => VecEnum.withName(kv._1) -> kv._2))).getOrElse(Double.NaN)
          loggerI.trace(f"  (\u001b[2m${mId}\u001b[0m) $rOrU-sim(${qr.qword}): idf=${idfModel.transform(qr.qword)}%.2f bm25=${bm25Tf(qr.dbScore, nWords)}%.2f sim=$mu%.2f")
        }

        Score(idfModel.transform(qr.qword), bm25Tf(qr.dbScore, nWords), qr.count)
      }
      // https://en.wikipedia.org/wiki/Okapi_BM25
      val searchTermScores = searchTermReprs.map(bm25)

      // arithmetic mean (no penalty for not matching all the query words; geo mean imposes about a 12% penalty
      // for missing 1 out of 3 query words and 15% for missing 2 out of 3)
      val amean = searchTermScores.map(x => x.idf * x.n * x.b).sum /
                  searchTermScores.map(x => x.idf * x.n      ).sum

      // geometric mean, computed with logs (using 0.1 here instead of 1.0 imposes a larger penalty on documents
      // that don't match all the query words; e.g. if there are 3 query words, a document that has 1 missing
      // will have about a 25% lower geo mean with 0.1 than with 1.0, and 2 missing out of 3 will be 50% lower)
      val ep = 0.01
      val gmean = math.exp(searchTermScores.map(x => x.idf * x.n * math.log(x.b + ep)).sum /
                           searchTermScores.map(x => x.idf * x.n                     ).sum) - ep

      // geometic mean, computed w/out logs (unstable)
      val gmean2 = math.pow(searchTermScores.map(x => math.pow(x.b + ep, x.idf * x.n)).product,
                      1.0 / searchTermScores.map(x =>                    x.idf * x.n ).sum) - ep

      val wmean = amean * (1.0 - anyVsAllArg) + gmean * anyVsAllArg

      // debugging
      // calculate cosine similarities for each of the search terms and sum them (no need to use geometric mean
      // here because every document is guaranteed to have some level of semantic similarity to each query term
      // regardless of whether the query term actually appears in the doc)
      var extraTermText = ""
      val mbSimilarity: Option[Double] = if (searchTermVecs.isEmpty || docVecs.isEmpty) None else {
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
        Some(weightedSum / idfs.sum) // always use arithmentic mean as geo mean doesn't make much sense w/ similarities, which range between [-1,1] rather than [0,inf)
      }

      loggerI.trace(f"  (\u001b[2m${mId}\u001b[0m) $rOrU-sim: wsim=${mbSimilarity.getOrElse(Double.NaN)}%.2f wscore=$wmean%.2f (a=$amean%.2f g=$gmean%.2f g2=$gmean2%.2f nWords=$nWords nScores=${searchTermScores.length})")

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

      ScoresAndText(math.max(wmean, 0.0).coalesce0, mbSimilarity.getOrElse(0.0), extraText, extraTermText)
    }
  }
}

object SearchResults {

  type typ = (Mark, String, Option[SearchRelevance])

  val logger = Logger(getClass)

  // A value between 0 and 1 that determines how much weight is put on the addition of search
  // scores for each query term ("any") vs. the product of scores ("all").  0 corresponds to 100%
  // "any" while 1 to 100% "all."
  case class AnyVsAllArg() extends OptionalInjectId[Double]("anyall", 0.5)

  // capital letter regular expression (TODO: https://github.com/Hamstoo/hamstoo/issues/68)
  val capitalRgx: Regex = s"[A-Z]".r.unanchored

  private val MAX_PREVIEW_DOC_LENGTH = 10000 // average word length is ~7 chars (including 1 space) => 1428 words
  private val PREVIEW_LENGTH = 150
  private val N_SPANS = 3
  private val MIN_PREFIX_LENGTH = 4
  val WHICH_PREVIEW_TEXT = 1 // -1 for old, 0 for none (disable it), 1 for new
  val DISABLED_PREVIEW = (0, Seq.empty[(Double, String)])

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

        // TODO: could we apply this algorithm in increments of 10000 chars, or use map to select local peaks rather than global peaks?
        val rawText = rawText0.take(MAX_PREVIEW_DOC_LENGTH)
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
        preproc/*.par*/.foreach { case (term, j) => // 1.399 or 1.332 seconds
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
        val smoothedImmutableCmp = tcountsCmp.indices/*.par*/.map { i =>
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

    /** Find all occurrences of any of q: Seq[String] in s: String and return a list of indexes of the occurrences. */
    @scala.annotation.tailrec
    private def findAll(s: String, q: Seq[String], shift: Int, is: Seq[(Int, Int)]): (Seq[(Int, Int)]) = {
      val mbTpl = (for {w <- q if w.nonEmpty} yield (s indexOf w, w.length)).filter(_._1 != -1).sortBy(_._1).headOption
      if (mbTpl.isEmpty) is else {
        val (i, l) = mbTpl.get
        val step = i + l
        val nshift = shift + step
        findAll(s drop step, q, nshift, is :+ (shift + i, nshift))
      }
    }

    /** Merge all adjacent indexes. */
    @scala.annotation.tailrec
    private def glueAll(is: Seq[(Int, Int)], js: Seq[(Int, Int)]): Seq[(Int, Int)] =
      if (is.size < 2) is ++ js
      else {
        val t = is.tail
        val (i11, i12) = is.head
        val (i21, i22) = t.head
        if (i21 - i12 < 2) glueAll((i11, i22) +: t.tail, js) else glueAll(t, is.head +: js)
      }

    /** Get up to 20 first spans of max length 160 with indexes in them. */
    private def groupAll(
                          is: Seq[(Int, Int)],
                          ks: Seq[((Int, Int), Seq[(Int, Int)])]): Seq[((Int, Int), Seq[(Int, Int)])] =
      if (is.isEmpty || ks.size == 20) ks else if (is.size == 1) (is.head -> is) +: ks else {
        val i1 = is.head._1
        val t = is.tail
        val n = t takeWhile (_._2 - i1 < PREVIEW_LENGTH + 1)
        if (n.isEmpty) groupAll(t, (is.head -> (is.head :: Nil)) +: ks)
        else groupAll(t drop n.size, ((i1, n.last._2) -> glueAll(is.head +: n, Nil)) +: ks)
      }

    /** Function for bold text html tags insertion around query terms occurrences. */
    private def formatSnippet(encPreview: String)(tup: ((Int, Int), Seq[(Int, Int)])): (Double, String) = tup match {
      // `i1` and `i2` are the beginning and end of the group of words to embolden,
      // `is` is a sequence of all of the individual words in the group to embolden
      case ((i1, i2), is) =>
        val m = (PREVIEW_LENGTH - (i2 - i1)) / 2 // number of chars before & after this group
      val start = math.max(0, i1 - m) // center the group in the PREVIEW_LENGTH window
      val end = math.min(encPreview.length, i2 + m)

        // recursively embolden query words
        @tailrec
        def embolden(s: String, is: Seq[(Int, Int)], score: Double = 0.0): (Double, String) = {
          if (is.isEmpty) (score, s)
          else {
            // compute word score (for sorting purposes) as sqrt(word.length) but penalize capital letters a little
            val word = s.substring(is.head._1, is.head._2)
            val nCaps = capitalRgx.findAllIn(word).length
            val wordScore = math.sqrt(word.length.toDouble - 0.25 * nCaps)

            // this requires `is` in reverse order so that the patching doesn't affect later offsets
            val patched = s.patch(is.head._2, "</b>", 0).patch(is.head._1, "<b>", 0)
            embolden(patched, is.tail, score + wordScore)
          }
        }

        // substring and adjust indices to match for this evidence
        val substrPreview = encPreview.slice(start, end)
        val isShifted = is.map(tup => tup._1 - start -> (tup._2 - start))
        val (score, str) = embolden(substrPreview, isShifted)
        (score, s"...$str...")
    }

    /** Generates the HTML preview of the text with emboldened query words. */
    def old(dbSearchScore: Double, rawText0: String): (Int, Seq[(Double, String)]) = {
    //def preview(text: String, querySeq: Seq[String]): String = {

      val querySeq = cleanedQuery.map(_._1)

      // Function for html tags encoding (use StringEscapeUtils.escapeHtml4 here instead?)
      val encode: String => String = _ replace("<", "&#60;") replace(">", "&#62;") trim
      val encText = encode(rawText0)

      def boundFindAll(querySeq: Seq[String]): Seq[(Int, Int)] =
        findAll(encText.toLowerCase, querySeq.map(encode(_).toLowerCase.replace("\"", "")), 0, Nil)

      @tailrec
      def wordsToBold(ps: Map[String, Int], words: Seq[(Int, Int)]): Seq[(Int, Int)] = {

        // look for prefixes if no full query words were found, this came about when the word "psychology" was
        // searched for and a document containing the word "psychological" was returned by Mongo
        if (words.nonEmpty) words else {

          // so if the query is "soft anarchy" then our sorted list on iteration #1 is [(anarchy, 7), (soft, 4)] and
          // we end up searching for "anarch" in the text, on iteration #2: [(anarch, 7*(6/7)^2=5.14), (soft, 4)],
          // #3: [(soft, 4), (anarc, 7*(5/7)^2=3.57)], #4: [(anarc, 7*(5/7)^2=3.57), (sof, 4*(3/4)^2=2.25))] ....
          val hd = ps.toSeq.sortBy { case (t1, t2) =>
            t2 * Math.pow(t2.toDouble / t1.length, 2) * (if (t2 < MIN_PREFIX_LENGTH) 0 else 1)
          }(Ordering.Double.reverse).head

          // shorten the prefix by a single char
          val newLen = hd._2 - 1

          val filtered: Seq[(Int, Int)] = for {
            // search for the prefix in the text
            tup@(s, e) <- boundFindAll(hd._1.take(newLen) :: Nil)

            // our current implementation is based on `words` being empty, but it could just as easily be based
            // on it being less than a certain size, so filter any words that we've already found
            if !words.exists { case (t1, t2) => s >= t1 && e <= t2 }
          } yield tup

          // infinite loop can occur if prefixes are allowed to get down to 0 length
          val psUpdated = ps.updated(hd._1, newLen)
          if (!psUpdated.exists(_._2 >= MIN_PREFIX_LENGTH)) words ++ filtered
          else wordsToBold(psUpdated, words ++ filtered)
        }
      }

      val embolded: Seq[(Int, Int)] =
        wordsToBold(querySeq.map(w => w -> w.length)(breakOut), boundFindAll(querySeq))

      // the first (Int, Int) element of this sequence of tuples is the full range of text in which each group of
      // wordsToBold falls, the second Seq[(Int, Int)] element is a sequence of the individual words in this group
      val grouped: Seq[((Int, Int), Seq[(Int, Int)])] = groupAll(embolded, Nil)

      // TODO: is it possible to do phrase search in mongo?
      if (grouped.isEmpty) {
        if (encText.isEmpty) (0, Seq((0, encText)))
        else if (encText.length < PREVIEW_LENGTH) (0, Seq((0, encText)))
        else (0, Seq((0, s"${encText take PREVIEW_LENGTH}...")))
      }
      else {
        // score earlier snippets higher (`grouped` is in reverse order)
        (0, grouped.map(formatSnippet(encText))
          .zipWithIndex.map { case ((score, str), i) => (score * math.sqrt(i + 1), str) }
          .sortBy(-_._1)
          .take(N_SPANS))
      }
    }
  }
}