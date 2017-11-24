package com.hamstoo.services

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI

import scala.collection.JavaConverters._
import akka.util.ByteString
import com.hamstoo.models.Page
import com.hamstoo.utils.MediaType
import org.apache.tika.metadata.{PDF, TikaCoreProperties}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.Logger
import play.api.libs.ws.{WSClient, WSResponse}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

/**
  * ContentRetriever companion class.
  */
object ContentRetriever {

  val logger = Logger(classOf[ContentRetriever])
  val titleRgx: Regex = "(?i)<title.*>([^<]+)</title>".r.unanchored

  /** Make sure the provided String is an absolute link. */
  def checkLink(s: String): String = if (s.isEmpty) s else Try(new URI(s)) match {
    case Success(uri) if uri.isAbsolute => uri.toASCIIString
    case Success(uri) => "http://" + uri.toASCIIString
    case Failure(t) => logger.info(s"String '$s' is probably not a URL; ${t.getMessage}"); s
  }

  /** Moved from hamstoo repo LinkageService class.  Used to take a MarkData as input and produce one as output. */
  def fixLink(url: String): String = Try(checkLink(url)).getOrElse("http://" + url)

  /** Implicit MimeType class implementing a method which looks up a RepresentationService and calls its `process`. */
  implicit class PageFunctions(private val page: Page) /*extends AnyVal*/ {

    /** Look for a RepresentationService that supports this mime type and let it construct a representation. */
    def getTitle: Option[String] = MediaType(page.mimeType) match {

      // using a different method here than in HTMLRepresentationService
      case mt if MediaTypeSupport.isHTML(mt) =>
        ByteString(page.content.toArray).utf8String match {
          case titleRgx(title) => Some(title.trim)
          case _ => None
        }

      // this is basically the same technique as in PDFRepresentationService
      case mt if MediaTypeSupport.isPDF(mt) || MediaTypeSupport.isText(mt) || MediaTypeSupport.isMedia(mt) =>
        val is: InputStream = new ByteArrayInputStream(page.content.toArray)
        val (contentHandler, metadata, parseContext, parser) = TikaInstance()
        parser.parse(is, contentHandler, metadata, parseContext)
        val titleKey = if (MediaTypeSupport.isPDF(mt)) PDF.DOC_INFO_TITLE else TikaCoreProperties.TITLE
        Option(metadata.get(titleKey)).filter(!_.isEmpty)

      case _ => None
    }
  }
}

/**
  * Tika-supported implementation of ContentRetriever.
  * All it does is `retrieve: String => Future[(MimeType, String)]`.
  */
class ContentRetriever(httpClient: WSClient)(implicit ec: ExecutionContext) {

  import ContentRetriever._

  /** Retrieve mime type and content (e.g. HTML) given a URL. */
  def retrieve(url: String): Future[Page] = {
    logger.debug(s"Retrieving URL '$url' with MIME type '${Try(MediaType(TikaInstance.detect(url)))}'")

    // switched to using `digest` only and never using `retrieveBinary` (issue #205)
    digest(url).map(x => Page(x._2.bodyAsBytes.toArray)).map { page =>
      val html = ByteString(page.content.toArray).utf8String
      println(html) // todo removethis line, leave space between existing lines
    val docJsoup = Jsoup.parse(html) //htmlParser.parseString(html)

      //Additional function to check frame and frameset, get content from frames and return as doc
      def loadFrame(frameElement: Element): Future[Element] = {
        retrieve(url + frameElement.attr("src")).map(page =>
          frameElement.html(ByteString(page.content.toArray).utf8String))
      }

      def checkFrameset(framesetElement: Element): List[Future[Element]] = {
        /* framesetElement.tagName match {
        case "frame" => contentRetriever.retrieve(url + framesetElement.attr("src"))
        case _ =>
      }*/
        framesetElement.children.iterator().asScala.toList.map(elementZipped => loadFrame(elementZipped))
      }

      val framesetElems = docJsoup.getElementsByTag("frameset")
      val loadedFramesets = new ArrayBuffer[List[Future[Element]]]()
      if (!framesetElems.isEmpty) {
        framesetElems.iterator().asScala.foreach(element =>
          if (element.parent().tag() != "frameset") {
            loadedFramesets += checkFrameset(element)
          })
      }


      val framesElems = docJsoup.getElementsByTag("frame")

      val loadedFrames = new ArrayBuffer[Future[Element]]

      if (!framesElems.isEmpty) {
        framesElems.iterator().asScala.foreach(element => {
          loadedFrames += loadFrame(element)
        })
      }

      val loadedAll: Future[List[Element]] = Future.sequence {
        (loadedFramesets.flatten ++= loadedFrames.toList).toList
      }

      loadedAll.map { _ =>
        page.copy(content = docJsoup.html().getBytes("UTF-8"))
      }
    }.flatten
  }

  val MAX_REDIRECTS = 8

  /** This code was formerly part of the 'hamstoo' repo's LinkageService. */
  def digest(url: String): Future[(String, WSResponse)] = {
    val link: String = checkLink(url)

    //@tailrec
    def recget(url: String, depth: Int = 1): Future[(String, WSResponse)] = {
      if (depth >= MAX_REDIRECTS)
        Future.failed(new IllegalArgumentException(s"Too many redirects for $url"))
      else {

        // The below call to WSRequest.get returns a Future, but if the URL is junk (e.g. chrome://extensions/), it throws
        // an exception, which it appears to throw from outside of a Future block.  This wouldn't be a problem except for
        // the fact that RepresentationActor.receive has contentRetriever.retrieve as its *first* Future in its
        // for-comprehension, which means this exception occurs outside of *all* of the desugared Future flatMaps.  To
        // remedy, this call either needs to not be the first Future in the for-comprehension (an odd limitation that
        // callers shouldn't really have to worry about) or be wrapped in a Try, as has been done here.
        Try(httpClient.url(url).withFollowRedirects(true).get).fold(Future.failed, identity).flatMap { res =>

          res.status match {
            // withFollowRedirects follows only 301 and 302 redirects.
            // We need to cover 308 - Permanent Redirect also. The new url can be found in "Location" header.
            case 308 =>
              res.header("Location") match {
                case Some(newUrl) =>
                  recget(newUrl, depth + 1)
                case _ =>
                  Future.successful((url, res))
              }
            case _ =>
              Future.successful((url, res))
          }
        }
      }
    }

    recget(link)
  }

  // TODO: do we even need this retrieveHTML function or can we always use retrieveBinary?
  /**
    * Returns the body of the HTTP response in a `Future` object.
    * Await.result was formerly being used here but that throws an exception on failure, which is not what we want,
    * then it was changed to Await.ready, which is better, but which there's no need for b/c we're returning a Future
    */
  /*private def retrieveHTML(url: String): Future[String] = {

    // The below call to WSRequest.get returns a Future, but if the URL is junk (e.g. chrome://extensions/), it throws
    // an exception, which it appears to throw from outside of a Future block.  This wouldn't be a problem except for
    // the fact that RepresentationActor.receive has contentRetriever.retrieve as its *first* Future in its
    // for-comprehension, which means this exception occurs outside of *all* of the desugared Future flatMaps.  To
    // remedy, this call either needs to not be the first Future in the for-comprehension (an odd limitation that
    // callers shouldn't really have to worry about) or be wrapped in a Try, as has been done here.
    Try(httpClient.url(url).get)
      .fold(Future.failed, identity) map { response =>
        if (response.status < 300 && response.status > 199) logger.debug(s"OK, received ${response.body take 100}")
        else logger.error(s"Received unexpected status ${response.status} : ${response.body.take(100)}")
        response.body//.getBytes
      }
  }*/

  /**
    * We do not retrieve text here because PDFs contain binary data which are later processed by
    * PDFRepresentationService.
    *
    * As of 2017-10-17 this is now returning 403s for some sites.  For example, these:
    *   http://marginalrevolution.com/marginalrevolution/2017/10/richard-thaler-wins-nobel.html and
    *   https://doc.akka.io/docs/akka/2.5/scala/stream/index.html
    * As of 2017-11-06 this has been causing repr-engine to fail.  See 'hamstoo' issue #202.
    */
  /*private def retrieveBinary(url: String): Future[Array[Byte]] = Future {
    // PDFs do not have page source that can be represented as text, so
    // to save text in appropriate encoding it will be required to use Apache Tika
    // deeply here to detect encoding of document
    //   http://tika.apache.org/1.5/api/org/apache/tika/detect/EncodingDetector.html
    // Below idea is the example but I am not sure if it is good because PDF might consist of
    // scanned images or can consist images inside text.
    val is = Try_would_be_needed_here(new URL(url)).openStream()
    //val encDet = new Icu4jEncodingDetector
    //val metadata = new Metadata()
    //val encodingCharset = encDet.detect(is, metadata)
    val byteArray = IOUtils.toByteArray(is)
    //val text = ByteString(byteArray).decodeString(encodingCharset)
    byteArray
  }*/
}
