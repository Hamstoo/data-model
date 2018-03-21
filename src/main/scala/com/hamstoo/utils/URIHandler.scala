package com.hamstoo.utils

import java.net.URI

import com.hamstoo.services.ContentRetriever.logger
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist

import scala.util.{Failure, Success, Try}

object URIHandler {

  /** Make sure the provided String is an absolute link. */
  def checkLink(s: String): String = if (s.isEmpty) s else Try(new URI(XSSDetection(s))) match {
    case Success(uri) if uri.isAbsolute => uri.toASCIIString
    case Success(uri) => "http://" + uri.toASCIIString
    case Failure(t) => logger.info(s"String '$s' is probably not a URL; ${t.getMessage}"); s
  }
  /** escape HTML code */
  def XSSDetection(url: String): String = Jsoup.clean(url, Whitelist.basic())

  /** Moved from hamstoo repo LinkageService class.  Used to take a MarkData as input and produce one as output. */
  def fixLink(url: String): String = Try(checkLink(url)).getOrElse("http://" + url)

  /** Check if it contain XSS content, it will detect any JS content */
  def isDangerous(s: String): Boolean = XSSDetection(s) != s
}
