/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import com.hamstoo.models.{MSearchable, RSearchable}
import com.hamstoo.models.Representation.{Vec, VecEnum, VecFunctions}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService => VecSvc}
import com.hamstoo.stream.Join.JoinWithable
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.google.inject.name.Named
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import org.slf4j.{LoggerFactory, Logger => Slf4jLogger}
import play.api.Logger

import scala.annotation.tailrec
import scala.collection.breakOut
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

/**
  * Define the (default) implementation of this facet.
  * @param reprsStream  A stream of a user's marks' representations.
  * @param query2Vecs   Semantic word vectors for each query term.
  */
@com.google.inject.Singleton
class SearchResults @Inject()(@Named(Query2VecsOptional.name) query2Vecs: Query2VecsType,
                              marksStream: MarksStream,
                              reprsStream: ReprsStream,
                              logLevel: LogLevelOptional.typ)
                             (implicit materializer: Materializer, ec: ExecutionContext,
                              idfModel: IDFModel)
    extends DataStream[(MSearchable, String, Option[Double])] {

  // can only obtain an EC from an ActorMaterializer via `.system`, not from a plain old Materializer
  //implicit val ec: ExecutionContext = materializer.system.dispatcher

  import SearchResults._

  // set logging level for this QuerySimilarities *instance*
  val logger0: Slf4jLogger = LoggerFactory.getLogger(classOf[SearchResults].getName.stripSuffix("$"))
  logLevel.foreach { lv => logger0.asInstanceOf[LogbackLogger].setLevel(lv); logger0.info(s"Overriding log level to: $lv") }
  val logger1 = new Logger(logger0)

  // the `{ case x => x }` actually does serve a purpose, it unpacks x into a 2-tuple
  override val hubSource: Source[Data[(MSearchable, String, Option[Double])], NotUsed] =
    marksStream().joinWith(reprsStream()) { case x => x }
      .mapAsync(2) { dat: Data[(MSearchable, ReprsPair)] =>

        // unpack the pair datum
        val (mark, reprs) = dat.oval.get.value
        val siteReprs = reprs.siteReprs
        val userReprs = reprs.userReprs

        // get uniquified `querySeq` and (future) vectors for all terms in search query `fsearchTermVecs`
        val (cleanedQuery, fsearchTermVecs) = query2Vecs
        val querySeq = cleanedQuery.map(_._1)

        // the `marks` collection includes the users own input (assuming the user chose to provide any input
        // in the first place) so it should be weighted pretty high if a word match is found, the fuzzy reasoning
        // behind why it is squared is because the representations collection is also incorporated twice (once
        // for database search score and again with vector cosine similarity), the silly 3.5 was chosen in order
        // to get a non-link mark (one w/out a repr) up near the top of the search results
        val mscore: Double = mark.score.getOrElse(0.0) /** MongoRepresentationDao.CONTENT_WGT*/ / cleanedQuery.length
        logger1.trace(f"\u001b[35m${mark.id}\u001b[0m: subj='${mark.mark.subj}'")

        // generate a single search result
        val fut = for(searchTermVecs <- fsearchTermVecs) yield {

          // compute scores aggregated across all search terms
          val (rscore, rsim, rText, rTermText) = searchTerms2Scores("R", mark.id, siteReprs, searchTermVecs)
          val (uscore, usim, uText, uTermText) = searchTerms2Scores("U", mark.id, userReprs, searchTermVecs, nWordsMult = 100)

          val isdefBonus = Seq(rscore, mscore, uscore).count(_ > 1e-10)
          val rAggregate = math.exp(rsim.getOrElse(0.0)) + math.max(rscore, 0.0)
          val mAggregate = math.exp(usim.getOrElse(0.0)) + math.max(uscore, 0.0) + math.max(mscore, 0.0)
          logger1.trace(f"  ${mark.id}: scores: agg(r/m)=$rAggregate%.2f/$mAggregate%.2f text-search(r/m/u)=$rscore%.2f/$mscore%.2f/$uscore%.2f similarity(r/u)=${rsim.getOrElse(Double.NaN)}%.2f/${usim.getOrElse(Double.NaN)}%.2f")

          (rAggregate.isNaN, mAggregate.isNaN, mark.score.isDefined) match {
            case (true, true, true) => // this first case shouldn't ever really happen
              val aggregateScore = mscore + isdefBonus
              val sc = mark.score.getOrElse(Double.NaN)
              val scoreText = f"Aggregate score: <b>$aggregateScore%.2f</b> (bonus=$isdefBonus), " +
                f"Raw marks database search score: <b>$sc%.2f</b>"
              val pr = preview(mark.mark.comment.getOrElse(""), querySeq)
              val pv = s"$scoreText<br>$pr" // debugging
              Some((mark, if (logger1.isDebugEnabled) pv else pr, Some(aggregateScore)))

            case (true, false, _) => // this second case will occur for non-URL marks
              val aggregateScore = mAggregate * 1.6 + isdefBonus
              val scoreText = f"Aggregate score: <b>$aggregateScore%.2f</b> (bonus=$isdefBonus), " +
                f"User content similarity: <b>exp(${usim.getOrElse(Double.NaN)}%.2f)</b>, " +
                f"Database search scores: M/U=<b>$mscore%.2f</b>/<b>$uscore%.2f</b>=<b>${mscore/uscore}%.2f</b>"
              val pr = preview(mark.mark.comment.getOrElse(""), querySeq)
              val pv = s"$scoreText<br>U-similarities: $uTermText&nbsp; $uText<br>$pr" // debugging
              Some((mark, if (logger1.isDebugEnabled) pv else pr, Some(aggregateScore)))

            case (false, true, _) => // this case will occur for old school bookmarks without any user content
              val aggregateScore = rAggregate * 1.4 + isdefBonus
              val scoreText = f"Aggregate score: <b>$aggregateScore%.2f</b> (bonus=$isdefBonus), " +
                f"URL content similarity: <b>exp(${rsim.getOrElse(Double.NaN)}%.2f)</b>, " +
                f"Database search scores: R=<b>$rscore%.2f</b>"
              val reprDocText = siteReprs.find(_.mbR.isDefined).flatMap(_.mbR).fold("")(_.doctext)
              val pr = preview(reprDocText, querySeq)
              val pv = s"$scoreText<br>R-similarities: $rTermText&nbsp; $rText<br>$pr" // debugging
              Some((mark, if (logger1.isDebugEnabled) pv else pr, Some(aggregateScore)))

            case (false, false, _) => // this case should fire for most marks--those with URLs

              // maybe want to do max(0, cosine)?  or incorporate antonyms and compute cosine(query-antonyms)?
              // because antonyms may be highly correlated with their opposites given similar surrounding words
              val aggregateScore = rAggregate + mAggregate + isdefBonus

              // Produce webpage text preview
              val scoreText = f"Aggregate score: <b>$aggregateScore%.2f</b> (bonus=$isdefBonus), " +
                f"Similarities: R=<b>exp(${rsim.getOrElse(Double.NaN)}%.2f)</b>, U=<b>exp(${usim.getOrElse(Double.NaN)}%.2f)</b>, " +
                f"Database search scores: R=<b>$rscore%.2f</b>, M/U=<b>$mscore%.2f</b>/<b>$uscore%.2f</b>=<b>${mscore/uscore}%.2f</b>"
              val reprDocText = siteReprs.find(_.mbR.isDefined).flatMap(_.mbR).fold("")(_.doctext)
              val pr = preview(mark.mark.comment.getOrElse("") + " " * PREVIEW_LENGTH + reprDocText, querySeq)
              val pv = s"$scoreText<br>" +
                s"R-similarities: $rTermText&nbsp; $rText<br>" +
                s"U-similarities: $uTermText&nbsp; $uText<br>$pr" // debugging

              Some((mark, if (logger1.isDebugEnabled) pv else pr, Some(aggregateScore)))

            case (_, _, false) => None
          }
        }

        fut.map { _.map(dat.withValue) }

      }.mapConcat(_.to[immutable.Iterable])
      .asInstanceOf[SourceType] // see "BIG NOTE" on JoinWithable

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
        logger1.trace(f"  $mId: $rOrU-sim: qword=${qr.qword} -> idf=${idfModel.transform(qr.qword)}%.2f * bm25=${bm25Tf(qr.dbScore, nWords)}%.2f")
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

      logger1.trace(f"  $mId: $rOrU-sim:   weighted-score=$score%.2f (s0=$score0%.2f s2=$score2%.2f nWords=$nWords nScores=${searchTermScores.length})")

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

  // capital letter regular expression (TODO: https://github.com/Hamstoo/hamstoo/issues/68)
  val capitalRgx: Regex = s"[A-Z]".r.unanchored

  // should these be put in a companion object? (probably doesn't matter given this is a singleton)
  private val PREVIEW_LENGTH = 150
  private val N_SPANS = 3
  private val MIN_PREFIX_LENGTH = 4

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
  def preview(text: String, querySeq: Seq[String]): String = {

    // Function for html tags encoding (use StringEscapeUtils.escapeHtml4 here instead?)
    val encode: String => String = _ replace("<", "&#60;") replace(">", "&#62;") trim
    val encText = encode(text)

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
      if (encText.isEmpty) encText
      else if (encText.length < PREVIEW_LENGTH) encText
      else s"${encText take PREVIEW_LENGTH}..."
    }
    else {
      // score earlier snippets higher (`grouped` is in reverse order)
      grouped.map(formatSnippet(encText))
        .zipWithIndex.map { case ((score, str), i) => (score * math.sqrt(i + 1), str) }
        .sortBy(-_._1)
        .map(_._2)
        .take(N_SPANS)
        .mkString("<br>")
    }
  }
}