package com.hamstoo.services

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID

import akka.util.ByteString
import com.gargoylesoftware.htmlunit.html.HtmlPage
import com.gargoylesoftware.htmlunit._
import com.hamstoo.models.Page
import com.hamstoo.utils.MediaType
import play.api.libs.ws.ahc.cache.CacheableHttpResponseStatus
import play.shaded.ahc.org.asynchttpclient.uri.Uri
//import net.ruippeixotog.scalascraper.browser.HtmlUnitBrowser
import org.apache.tika.metadata.{PDF, TikaCoreProperties}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.Logger
import play.api.libs.ws.ahc.{AhcWSResponse, StandaloneAhcWSResponse}
import play.api.libs.ws.{WSClient, WSResponse}
import play.shaded.ahc.org.asynchttpclient.Response.ResponseBuilder
import play.shaded.ahc.org.asynchttpclient.{HttpResponseBodyPart, Response}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

/**
  * ContentRetriever companion class.
  */
object ContentRetriever {

  val logger = Logger(classOf[ContentRetriever])
  val titleRgx: Regex = "(?i)<title.*>([^<]+)</title>".r.unanchored
  
  // %s - variable to be changed by String.format (quoted to avoid regex errors)
  val REGEX_FIND_WORD = ".*%s.*"
  val incapsulaRgx: Regex = REGEX_FIND_WORD.format("ncapsula").r.unanchored
  // if Incapsula incident detected and trying to use HtmlUnit, then Sitelock Captcha appears and HtmlUnit just hangs
  // so detect incident ahead of time
  val incapsulaIncidentRgx: Regex = REGEX_FIND_WORD.format(raw"Incapsula\sincident\sID").r.unanchored
  // standalone Sitelock Captcha for case when it raises without Incapsula,
  // not sure but it seems Sitelock is using Google's reCAPTCHA
  val sitelockCaptchaRgx: Regex = REGEX_FIND_WORD.format("Sitelock").r.unanchored
  val incapsulaWAFRgx: Regex = REGEX_FIND_WORD.format(raw"content\.incapsula\.com").r.unanchored

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

  /** Used by PDFRepresentationService (repr-engine) and by MarksController (hamstoo). */
  def getNameFromFileName(link: String): String = {
    val i1 = link.lastIndexOf('/') + 1
    val i2 = link.lastIndexOf('.')
    if (i2 > i1) link.substring(i1, i2) else link.substring(i1)
  }

  class CaptchaException extends Exception()

  // this exception is thrown when (re)Captcha is detected on a web page
  case class SitelockException() extends CaptchaException

  // this exception is throw if Incapsula has blacklisted our IP or PC or for some other reason
  case class IncapsulaIncidentException() extends CaptchaException

  // this exception is thrown if HtmlUnit got a network status code error (even after a few retries)
  case class HtmlUnitFailingHttpStatusCodeException(msg: String, cause: Throwable) extends Exception(msg, cause)
}

/**
  * Tika-supported implementation of ContentRetriever.
  * All it does is `retrieve: String => Future[(MimeType, String)]`.
  */
class ContentRetriever(httpClient: WSClient)(implicit ec: ExecutionContext) {

  import ContentRetriever._

  /** Retrieve mime type and content (e.g. HTML) given a URL. */
  def retrieve(userId: UUID, id: String, reprType: String, url: String): Future[Page] = {
    val mediaType = MediaType(TikaInstance.detect(url))
    logger.debug(s"Retrieving URL '$url' with MIME type '${Try(mediaType)}'")

    // switched to using `digest` only and never using `retrieveBinary` (issue #205)
    val futPage = digest(url).map { case(_, wsResp) =>
      Page(userId, id, reprType, wsResp.bodyAsBytes.toArray)
    }

    // check if html, than try to load frame tags if they are found in body
    if (!MediaTypeSupport.isHTML(mediaType)) futPage else {
      futPage.flatMap { page =>
        // `loadFrames` detects and loads individual frames and those in framesets
        // and puts loaded data into initial document
        loadFrames(url, page).map { framesLoadedHtml =>
          page.copy(content = framesLoadedHtml._1.getBytes("UTF-8"))
        }
      }
    }
  }

  /** Additional function to check frame and frameset tags, get content from frames and return as doc. */
  def loadFrames(url: String, page: Page): Future[(String, Int)] = {
    val html = ByteString(page.content.toArray).utf8String
    val docJsoup = Jsoup.parse(html)

    // simple method to retrieve data by url
    // takes Element instance as parameter and
    // sets loaded data into content of that Element instance of docJsoup val
    def loadFrame(frameElement: Element): Future[Element] = {
      retrieve(page.userId, page.id, page.reprType, url + frameElement.attr("src")).map { page =>
        frameElement.html(ByteString(page.content.toArray).utf8String)
      }
    }

    // find all frames in framesets and load them
    val framesElems = docJsoup.getElementsByTag("frame").asScala.toIterator
    val loadedFrames = framesElems.map(loadFrame)

    // the data is all set into correct Elements in loadFrame method which takes Element instance as parameter
    // and changes data inside that element (lf.size is only used in ContentRetrieverTests)
    Future.sequence(loadedFrames).map(lf => (docJsoup.html(), lf.size))
  }

  /**
    * This method is called if "incapsula" keyword found in page response.  Soon new WAFs should added to be detected.
    * The method uses HtmlUnit *testing* tool which runs JavaScript scripts and waits until all scripts are loaded--
    * it runs loaded scripts as well.  What provides necessary calculations to bypass WAF.
    */
  def bypassWAF(url: String): Future[WSResponse] = {

    // these settings are important to run JavaScript in loaded page by emulated browser
    val webClient: WebClient = new WebClient(BrowserVersion.EDGE)
    webClient.setAjaxController(new AjaxController(){
      override def processSynchron(page: HtmlPage, request: WebRequest, async: Boolean) = true
    })

    // this low timeout is required because WAFs can include several redirects and heavy JavaScripts to be loaded
    // from Wordpress CMS sites (usually used with Incapsula plugin), it depends on client/server bandwidth and
    // server capacity (PHP sites in general usually consume lots of resources)
    webClient.waitForBackgroundJavaScript(20000)
    webClient.getOptions.setJavaScriptEnabled(true)
    webClient.getOptions.setThrowExceptionOnFailingStatusCode(false);
    webClient.getOptions.setThrowExceptionOnScriptError(false);
    webClient.getOptions.setCssEnabled(false)
    webClient.getOptions.setRedirectEnabled(true)
    webClient.getOptions.setUseInsecureSSL(true)

    // HtmlUnit throws too many non-critical exceptions so we perform a few retries.  Many Exceptions are probably
    // thrown by HtmlUnit because of HTML inconsistencies.  Incapsula has also a plugin for Wordpress engine, which
    // is a source of plenty of errors.  This code can be tested with the following URL: http://www.arnoldkling.com/blog/does-america-over-incarcerate/
    def retryGetPage[T](fn: () => T, nRetries: Int = 3): Try[T] = Try(fn()).recoverWith {
      case e: FailingHttpStatusCodeException => {
        if (nRetries == 0) Failure(HtmlUnitFailingHttpStatusCodeException(e.getMessage, e)) else {
          logger.info(s"Unable to get URL ${url.take(60)}; ${e.getClass.getName}: ${e.getMessage}; $nRetries retries remaining")
          retryGetPage(fn, nRetries = nRetries - 1)
        }
      }
    }

    val response: Try[Response] = retryGetPage[HtmlPage](() => webClient.getPage(url).asInstanceOf[HtmlPage]).map { htmlPage =>
        val html = htmlPage.asText()
        val contentByte = html.toCharArray.map(_.toByte)
        new ResponseBuilder().accumulate(new HttpResponseBodyPart(true) {
          override def getBodyPartBytes: Array[Byte] = contentByte
          override def length(): Int = contentByte.length
          override def getBodyByteBuffer: ByteBuffer = ByteBuffer.allocate(contentByte.length)
          }).accumulate(new CacheableHttpResponseStatus(Uri.create(url), 200, "200", "https"))build()
      }

    Future.fromTry(response).map(r => AhcWSResponse(StandaloneAhcWSResponse(r)))
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
            case _ => checkKnownProblems(url, res).map((url, _))
          }
        }
      }
    }
    recget(link)
  }

  /**
    * Detects known WAFs and Captchas.
    *
    * 2017-12-8 via Slack
    * Alex - our Incapsula source is useless without captcha cracker because prod ip is already under captcha
    * Fred - We can switch prod ip. Do you think that would do it? The Incapsula source should work from my local
    *   machine right?
    * Alex - If you tried before this website unsuccessfully than I think it won’t run
    *   I am afraid that there are many cases after which incapsula raises captcha
    *   For example, if user requests same website twice during 1 or two minutes
    *   So Incapsula will block the website during testing or watching how our source works
    */
  def checkKnownProblems(url: String, res: WSResponse): Future[WSResponse] = res.body match {

    //case body if body.matches(incapsulaRgx.toString()) => // disabled b/c not working; sometimes passes to next regex case
    case body if body.contains("ncapsula") => body match {

      // "Sitelock" Captcha raised when Incapsula suspects crawler
      case s if s.matches(sitelockCaptchaRgx.toString) =>
        Future.failed(SitelockException())

      // "Incapsula incident" is raised if Incapsula detected suspected behavior and blacklisted ip or pc, or something else
      case s if s.matches(incapsulaIncidentRgx.toString) =>
        Future.failed(IncapsulaIncidentException())

      // if no problems detected except Incapsula WAF raised => bypass WAF
      // html page with Incapsula scripts are small, usually < 1000 chars
      case s if s.contains("content.incapsula.com") =>
        bypassWAF(url)

      // if no incident detected, no Incapsula script detected and no Incapsula Captcha detected then
      // it's a probably word "Incapsula" in text jut process in regular way
      case _ => Future.successful(res)
    }

    // TODO: add detection of popular WAFs and invoke HtmlUnit to bypass them when they are detected
    // TODO: need to find finger prints of the following:
    /*F5 BIG IP WAF,
      Citrix Netscaler WAF: "ns_af" in cookie
      Sucuri,
      Modsecurity,
      Imperva Incapsula,
      PHP-IDS (PHP Intrusion  Detection System),
      Quick Defense,
      AQTRONIX WebKnight (For IIS and based on ISAPI filters),
      Barracuda WAF
      */
    /*case waf2Rgx(body) =>
      case waf3Rgx(body) =>
      case waf4Rgx(body) =>
      case waf5Rgx(body) =>
      case waf6Rgx(body) =>
      case waf7Rgx(body) =>
      case waf8Rgx(body) =>
      case waf9Rgx(body) =>*/

    // if no WAFs or Captchas detected then just process representation
    case _ => Future.successful(res)
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