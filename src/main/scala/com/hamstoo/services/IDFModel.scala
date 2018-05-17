package com.hamstoo.services

import java.io._
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import java.util.{Locale, Scanner}

import com.google.inject.{Inject, Singleton}
import com.google.inject.name.Named
import com.hamstoo.services.IDFModel.ResourcePathOptional
import com.hamstoo.stream.InjectId
import com.hamstoo.utils
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import com.hamstoo.utils.cleanly

import scala.collection.mutable
import scala.util.matching.UnanchoredRegex

object IDFModel {

  // https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
  object ResourcePathOptional extends InjectId[Option[String]] { final val name = "idfs.resource.path" }
}

/**
  * IDF calculator.
  *
  * Fitting IDFs for [text8.zip](http://mattmahoney.net/dc/text8.zip) (see also:
  * http://mattmahoney.net/dc/textdata) with an assumption of 300 words per document
  * results in IDFs that range between 0.000018 ("of") and 9.3 ("transrapid").
  *
  * These values are consistent with those returned by the IDF calculator at IBM, which
  * ranged between 0.005 and 15.  The upper bound is an indicator of the corpus size
  * (and/or the base of the logarithm and/or the minimum number of required documents).
  * For example, assuming MIN_DOC_FREQ of 2, `(2+1)*e^15 => 10mm` documents.  Note however
  * that the difference between log(10mm/3) and log(10mm/4) is very small so there's
  * really no need to save the IDFs for such low frequency words.  Also there's really
  * no need to use such a huge corpus because we mostly care about reducing the potency
  * of high frequency words, enough of which will appear in any reasonably sized corpus.
  *
  * BM25 may be a better alternative to IDF: https://en.wikipedia.org/wiki/Okapi_BM25
  * http://opensourceconnections.com/blog/2015/10/16/bm25-the-next-generation-of-lucene-relevation/
  * http://www.benfrederickson.com/distance-metrics/
  */
@Singleton
class IDFModel @Inject() (@Named("idfs.resource") zipfileResource: String,
                          @Named("idfs.resource.path") opzipfilepath: ResourcePathOptional.typ) {

  val logger: Logger = Logger(classOf[IDFModel])

  // constants
  val MIN_IDF = 0.005 // IDFs cannot be below this value
  val MIN_DOC_FREQ = 4 // words must appear in at least 4 documents (helps to limit the map size)
  val CHARSET = "UTF-8" // for JSON serialization

  // the IDFs
  type MapType = Map[String, Double]
  var idfs: MapType = _
  var maxIdf: Double = _

  // Load IDFs in from a zipped JSON file.
  // get resource file off the classpath if not explicitly provided
  val zipfilepath: String = opzipfilepath.getOrElse('/' + zipfileResource)
  val inputStream: InputStream = opzipfilepath.fold(getClass.getResourceAsStream(zipfilepath))(new FileInputStream(_))
  val infilepath: String = zipfilepath.substring(0, zipfilepath.length - 4) // remove ".zip"
  logger.info(s"Loading IDFs from $zipfilepath")
  cleanly(new ZipInputStream(inputStream))(_.close) { in =>
    in.getNextEntry // move to the first/only compressed file in the zip archive
    val js: JsValue = Json.parse(in)
    idfs = js.as[MapType]
    maxIdf = idfs.maxBy(_._2)._2
  }

  /** Returns the IDF of the given word. */
  def transform(word: String): Double = {
    math.max(MIN_IDF, idfs.getOrElse(word, {
      // try to ignore words containing punctuation or digits by assigning them MIN_IDF, note however that this
      // will include multiple-word terms, like european_otter, which *are* processed correctly by word vec code
      word match {
        case utils.rgxAlpha(_*) => MIN_IDF
        case _ => maxIdf
      }
    }))
  }

  /**
    * Fit IDFs given a zipfilepath.
    */
  def fit(zipfilepath: String): Unit = {

    /** Documents iterator/generator. */
    class Documents(zipfilepath: String) extends Iterator[Seq[String]] {

      // a document is defined as 300 words
      private val docSize = 300
      private val doc = Array.fill[String](docSize)("")

      // load the zip file into a Scanner
      private val zipStream = new ZipInputStream(new FileInputStream(zipfilepath))
      zipStream.getNextEntry // move to the first file in the zip archive
      private val scanner = new Scanner(zipStream).useDelimiter(" +")

      /** Some elements of second-to-last document may be reused in last, but who cares. */
      def next: Seq[String] = {
        var i = 0
        while (scanner.hasNext && i < docSize) {
          doc(i) = scanner.next
          i += 1
        }
        doc
      }

      def hasNext: Boolean = scanner.hasNext
    }

    // unique/distinct words in each document
    val documents: Iterator[Seq[String]] = new Documents(zipfilepath).map { seq: Seq[String] =>
      seq.map(_.toLowerCase(Locale.ENGLISH)).distinct
    }

    // document frequencies for each word (this takes about 10s for text8.zip)
    val freqs = documents.flatten.foldLeft(
      mutable.HashMap.empty[String, Int].withDefaultValue(0)) { (map, word) => {
      map(word) += 1
      map
    }
    }.filter(_._2 >= MIN_DOC_FREQ)

    // assume that there is at least one word that appears in every document (e.g. "of") and use
    // that plus 1 as the magnitude of the set of documents |D|
    //   text8.zip (docSize=300): |D|=56200, unfiltered |W|=253854,
    //     filtered(MIN_DOC_FREQ=2) |W|=125809, filtered(MIN_DOC_FREQ=3) |W|=90823,
    val numDocs1: Double = freqs.maxBy(_._2)._2 + 1
    logger.info(f"Number of unique words: ${freqs.size}; (estimated) number of documents: $numDocs1%.0f")

    // log( (|D| + 1) / (DF(w,D) + 1) )
    //   [https://spark.apache.org/docs/2.1.0/ml-features.html#tf-idf]
    // [1. + log(N / (1. + p)) for p in data.groupby('userid').size()]
    //   [http://www.benfrederickson.com/distance-metrics/]
    idfs = freqs.map { case (k, v) => k -> math.log((numDocs1 + 1) / (v + 1)) }.toMap
    maxIdf = idfs.maxBy(_._2)._2

    //    println(idfs("anarchy"))  // 6.3723416378499484
    //    println(idfs("dirt"))     // 6.6192017157814735
    //    println(idfs("the"))      // 0.0056206487689293445
    //    println(idfs("and"))      // 0.0034400126607507693
    //    println(idfs.maxBy(_._2)) // (transrapid,9.327251916883684)
    //    println(idfs.minBy(_._2)) // (of,1.7793436001860186E-5)
  }

  /**
    * Serialize IDFs out to a zipped JSON file.
    *
    * @param zipfilepath A .json.zip filename.
    */
  def dump(zipfilepath: String): Unit = {
    val outfilepath = zipfilepath.substring(0, zipfilepath.length - 4) // remove ".zip"
    logger.info(s"Dumping IDFs to $zipfilepath")
    cleanly(new ZipOutputStream(new FileOutputStream(zipfilepath)))(_.close) { out =>
      out.putNextEntry(new ZipEntry(outfilepath))
      cleanly(new OutputStreamWriter(out, CHARSET))(_.close) {
        _.write(Json.prettyPrint(Json.toJson(idfs)))
      }
    }
  }
}
