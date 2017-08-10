package com.hamstoo.services

import java.util.{Locale, UUID}

import com.hamstoo.daos.MongoVectorsDao
import com.hamstoo.models.Representation.Vec
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws._
import spray.caching.{Cache, LruCache}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Try
import scala.util.matching.Regex

object Vectorizer {
  // for testing only
  var gCount: Int = 0
  var dbCount: Int = 0
  var fCount: Int = 0
}

class Vectorizer(httpClient: WSClient, vectorsDao: MongoVectorsDao, vectorsLink: String) {
  
  // should be a 2-letter language code, e.g. "en"
  // TODO: https://github.com/Hamstoo/hamstoo/issues/68
  val ENGLISH: String = Locale.ENGLISH.getLanguage

  // define caches for calls out to the vector service
  val MAX_CAPACITY: Int = 2048
  val spcrRgx = """[-\/_\+—]|(\.\s+)|([\s,:;?!…]\s*)|(\.\.\.\s*)|(\s*["“”\(\)]\s*)"""
  val termRgx: Regex = s"[^a-z]*([a-z]+(($spcrRgx|[\\.'’])[a-z]+)*+)".r.unanchored

  def newCache[T](): Cache[T] = LruCache[T](maxCapacity = MAX_CAPACITY, initialCapacity = MAX_CAPACITY / 4)

  val lookupCache: Cache[Option[Vec]] = newCache()
  val standardizeCache: Cache[Option[String]] = newCache()
  val sAndLCache: mutable.Map[(String, String), Option[Vec]] = scala.collection.mutable.HashMap()

  /**
    * Lookup a single term or word.
    */
  def lookup(term: String): Future[Option[Vec]] = lookupCache(term) {
    val link = s"$vectorsLink/$ENGLISH/$term"
    httpClient.url(link).get map handleResponse(_.json.as[Vec])
  }

  /**
    * Post out to Python's conceptnet5.vectors.standardize_uri so as to avoid
    * re-implementing it in Scala.
    * the system standartizes uri to bring the uri to appropriate view of REST endpoint,
    * f.e.like in method standardizePost , i.e. s"$vectorsLink/$endpoint/$uuid"

    */
  def standardizeUri(language: String, term: String): Future[Option[String]] = standardizeCache((language, term)) {
    val (link, data) = standardizePost(language, term, "standardized_uri")
    httpClient.url(link).post(data) map handleResponse(_.json.\("uri").as[String])
  }

  /**
    * Standardize URI and lookup corresponding vector all in one API call.
    */
  def sAndL(language: String, term: String): Option[Vec] = {
    val (link, data) = standardizePost(language, term, "standardize_and_lookup")

    def fetch(rec: Boolean): Option[Vec] = {
      if (rec) synchronized(Thread.sleep(1000)) // wait for conceptnet-vectors to become online/responsive
      val future = httpClient.url(link).post(data) map handleResponse(_.json.as[Vec])
      Try(Await.result[Option[Vec]](future, Duration.Inf)) getOrElse fetch(true)
    }

    sAndLCache.getOrElseUpdate((language, term), fetch(false))
  }

  /**
    * Vector lookup with caching in database. This method looks up the term first, then the URI, and
    * only queries the vectors service if no cached entry found.
    */
  def dbCachedLookupFuture(language: String, term: String): Future[Option[(Vec, String)]] =

    // `mtch` appears to have trailing punctuation removed (was that the intent?)
    termRgx.findFirstMatchIn(term.toLowerCase(Locale.ENGLISH)).map { mtch =>

      // `standardizedTerm` appears to have leading punctuation removed (was that the intent?)
      val standardizedTerm = mtch.group(1).replaceAll("’", "'").replaceAll(s"($spcrRgx)+", "_")
      val uri = s"/$language/$standardizedTerm"
      //      println(s"Match [$standardizedTerm] for term [$term]")

      def fetch(rec: Boolean): Future[Option[Vec]] = {
        if (rec) synchronized(Thread.sleep(100)) // if recurring wait for conceptnet-vectors to become responsive
        httpClient.url(s"$vectorsLink$uri").get map handleResponse(_.json.as[Vec]) recoverWith {
          case _: NumberFormatException => Future.successful(None)
          //              case _: Throwable => fetch(true)
        }
      }

      vectorsDao.getUri(uri) flatMap {
        case Some(ve) =>
          Vectorizer.dbCount += 1
          Future.successful(ve.vector.map((_, standardizedTerm)))
        case None =>
          Vectorizer.fCount += 1
          val future = fetch(false)
          future map (vectorsDao.addUri(None, uri, _))
          future map (_.map((_, standardizedTerm)))
      }
    }.getOrElse(Future.successful(None))

  /** Preserves original `dbCachedLookup` behavior: what does the future hold? */
  def dbCachedLookup(language: String, term: String): Option[(Vec, String)] =
    Await.result(dbCachedLookupFuture(language, term), Duration.Inf)

  /**
    * Handle vector response from APIs that return vectors.
    */
  private def handleResponse[T](f: WSResponse => T)(response: WSResponse): Option[T] = {
    response.status match {
      case 200 => Some(f(response))
      case 404 => None
      case 500 => None
      case x =>
        println(s"Unexpected vector service response: $x")
        throw new Exception
    }
  }

  /**
    * Prepare `link` and `data` to post to one of the standardize endpoints.
    * * the system standartizes post uri to bring the uri to appropriate view of REST endpoint,
    * f.e. s"$vectorsLink/$endpoint/$uuid"
    */
  private def standardizePost(language: String, term: String, endpoint: String): (String, JsObject) = {

    Vectorizer.gCount += 1

    val uuid: UUID = UUID.randomUUID()
    val link = s"$vectorsLink/$endpoint/$uuid"
    val data = Json.obj("language" -> language, "term" -> term)
    (link, data)
  }
}
