/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.services

import java.util.Locale

import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.WordVectorDao
import com.hamstoo.models.Representation.Vec
import com.hamstoo.utils.fNone
import play.api.Logger
import play.api.libs.ws._

import scala.collection.mutable
import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
import play.api.http.Status

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.matching.Regex

object Vectorizer {
  // for testing only
  var dbCount: Int = 0
  var fCount: Int = 0

  val logger: Logger = Logger(classOf[Vectorizer])
}

/**
  * This class is a wrapper around the MongoVectorsDao and the conceptnet-vectors service.  It's primary function
  * is to abstract away the difference between the two so that the user doesn't have to know where the vectors
  * are coming from.
  *
  * "Guice can create bindings for concrete types by using the type's injectable constructor."
  *   [https://github.com/google/guice/wiki/JustInTimeBindings]
  */
@Singleton
class Vectorizer @Inject()(httpClient: WSClient,
                           vectorsDao: WordVectorDao,
                           @Named("vectors.link") vectorsLink: String) {

  import Vectorizer._

  // reinitialize these so that we can use them in Akka systems when actors get restarted
  dbCount = 0
  fCount = 0

  // define caches for calls out to the vector service
  val MAX_CAPACITY: Int = 2048
  val spcrRgx = """[-\/_\+—]|(\.\s+)|([\s,:;?!…]\s*)|(\.\.\.\s*)|(\s*["“”\(\)]\s*)"""
  val termRgx: Regex = s"[^a-z]*([a-z]+(($spcrRgx|[\\.'’])[a-z]+)*+)".r.unanchored

  val sAndLCache: mutable.Map[(String, String), Option[Vec]] = scala.collection.mutable.HashMap()

  /**
    * Lookup a single term or word.
    */
  /*@deprecated("Deprecated in favor of DB mirrored lookup.", "0.9.0")
  def lookup(term: String): Future[Option[Vec]] = {
    val link = s"$vectorsLink/${Locale.ENGLISH.getLanguage}/$term"
    httpClient.url(link).get map handleResponse(_.json.as[Vec])
  }*/

  /**
    * Post out to Python's conceptnet5.vectors.standardize_uri so as to avoid re-implementing it in Scala.
    * the system standartizes uri to bring the uri to appropriate view of REST endpoint, f.e.like in method
    * standardizePost, i.e. s"{vectorsLink}/{endpoint}/{uuid}"
    */
  /*@deprecated("Deprecated in favor of DB mirrored lookup.", "0.9.0")
  def standardizeUri(language: String, term: String): Future[Option[String]] = {
    val (link, data) = standardizePost(language, term, "standardized_uri")
    httpClient.url(link).post(data) map handleResponse(_.json.\("uri").as[String])
  }*/

  /**
    * Standardize URI and lookup corresponding vector all in one API call.
    */
  /*@deprecated("Deprecated in favor of DB mirrored lookup.", "0.9.0")
  def sAndL(language: String, term: String): Option[Vec] = {
    val (link, data) = standardizePost(language, term, "standardize_and_lookup")

    def fetch(rec: Boolean): Option[Vec] = {
      if (rec) synchronized(Thread.sleep(1000)) // wait for conceptnet-vectors to become online/responsive
      val future = httpClient.url(link).post(data) map handleResponse(_.json.as[Vec])
      Try(Await.result[Option[Vec]](future, Duration.Inf)) getOrElse fetch(true)
    }

    sAndLCache.getOrElseUpdate((language, term), fetch(false))
  }*/

  /**
    * Vector lookup with caching in database. This method looks up the term first, then the URI, and
    * only queries the vectors service if no cached entry found.
    */
  def dbCachedLookupFuture(locale: Locale, term: String): Future[Option[(Vec, String)]] = {

    val verbose: Boolean = Vectorizer.dbCount < 100 || Vectorizer.dbCount % 100 == 0

    // `mtch` appears to have trailing punctuation removed (was that the intent?)
    termRgx.findFirstMatchIn(term.toLowerCase(locale)).fold(fNone[(Vec, String)]) { mtch =>

      // `standardizedTerm` appears to have leading punctuation removed (was that the intent?)
      val standardizedTerm0 = mtch.group(1).replaceAll("’", "'").replaceAll(s"($spcrRgx)+", "_")

      // the "Bayes' Theorem" Wikipedia page used to contain the following text "ure Methods. 12 (4): 277&ndash, 8."
      // and a lookup of the `/en/ndash` URI causes an exception and 500 response from the service for some reason
      val standardizedTerm = if (standardizedTerm0 == "ndash") "-" else standardizedTerm0

      // `locale.getLanguage` should be a 2-letter language code, e.g. "en"
      // TODO: https://github.com/Hamstoo/hamstoo/issues/68
      val uri = s"/${locale.getLanguage}/$standardizedTerm"
      //println(s"Match [$standardizedTerm] for term [$term]")

      /** If a word vec isn't in the DB, then attempt to fetch it from the conceptnet-vectors service. */
      def fetch(rec: Boolean): Future[Option[Vec]] = {
        if (rec) synchronized(Thread.sleep(100)) // if recursing, wait for conceptnet-vectors to become responsive
        httpClient.url(s"$vectorsLink$uri").get.map(handleResponse(_.json.as[Vec])).recoverWith {
          case _: NumberFormatException => fNone
          //case _: Throwable => fetch(true)
        }
      }

      if (verbose) logger.trace(s"Looking up URI '$uri'")
      vectorsDao.retrieve(uri) flatMap {
        case Some(ve) =>
          if (verbose) logger.trace(s"Successful database vector lookup for URI '$uri'")
          Vectorizer.dbCount += 1
          Future.successful(ve.vector.map((_, standardizedTerm)))
        case None =>
          Vectorizer.fCount += 1
          if (verbose) logger.trace(s"Fetching URI '$uri' from service API")
          for {
            mbVec <- fetch(false)
            _ = if (verbose) logger.trace(s"Successful service API vector lookup for URI '$uri': ${mbVec.map(_.take(3))}")
            _ <- vectorsDao.addUri(uri, mbVec)
          } yield mbVec.map(_ -> standardizedTerm)
      }
    }
  }

  /** Preserves original `dbCachedLookup` behavior: what does the future hold? */
  @deprecated("Deprecated in favor of dbCachedLookupFuture.", "0.9.11")
  def dbCachedLookup(locale: Locale, term: String): Option[(Vec, String)] =
    Await.result(dbCachedLookupFuture(locale, term), 7 seconds)

  /** Handle vector response from APIs that return vectors. */
  private def handleResponse[T](f: WSResponse => T)(response: WSResponse): Option[T] = {
    response.status match {
      case Status.OK => Some(f(response))
      case Status.NOT_FOUND => None
      //case Status.INTERNAL_SERVER_ERROR => None
      case x =>
        val msg = s"Unexpected vector service response: $x"
        logger.warn(msg)
        throw new Exception(msg)
    }
  }

  /**
    * Used for (1) testing the `health` endpoint and (2) during repr-engine startup to wait until conceptnet-vectors
    * container has started, which can take a long time if repr-engine starts processing reprs right away.
    */
  def health: Future[Boolean] =
    httpClient.url(s"$vectorsLink/health").get
      .map(handleResponse(_ => ()))
      .map(_.nonEmpty)
      // this `recover` means that it should be impossible for this function to return a Future.failed
      .recover { case _ => false }

  /**
    * Prepare `link` and `data` to post to one of the standardize endpoints.
    * * the system standartizes post uri to bring the uri to appropriate view of REST endpoint,
    * f.e. s"$vectorsLink/$endpoint/$uuid"
    */
  /*private def standardizePost(language: String, term: String, endpoint: String): (String, JsObject) = {

    Vectorizer.gCount += 1

    val uuid: UUID = UUID.randomUUID()
    val link = s"$vectorsLink/$endpoint/$uuid"
    val data = Json.obj("language" -> language, "term" -> term)
    (link, data)
  }*/
}
