package com.hamstoo.services

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.net.{MalformedURLException, URI}
import java.nio.ByteBuffer
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import akka.util.ByteString
import com.gargoylesoftware.htmlunit.html.HtmlPage
import com.gargoylesoftware.htmlunit._
import org.scalatest.fixture
import play.api.libs.ws.ahc.{AhcWSResponse, StandaloneAhcWSResponse}
import play.shaded.ahc.org.asynchttpclient.HttpResponseBodyPart
import play.shaded.ahc.org.asynchttpclient.Response.ResponseBuilder
//import com.gargoylesoftware.htmlunit.BrowserVersion
import com.hamstoo.models.Page
import com.hamstoo.utils.MediaType
//import net.ruippeixotog.scalascraper.browser.HtmlUnitBrowser
import org.apache.tika.metadata.{PDF, TikaCoreProperties}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import play.api.Logger
import play.api.libs.ws.{WSClient, WSResponse}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import ContentRetriever._

/**
  * ContentRetriever companion class.
  */
object ContentRetriever {

  val logger = Logger(classOf[ContentRetriever])
  val titleRgx: Regex = "(?i)<title.*>([^<]+)</title>".r.unanchored
  // (?i) - ignorecase
  // .*? - allow (optinally) any characters before
  // \b - word boundary
  //%s - variable to be changed by String.format (quoted to avoid regex errors)
  // \b - word boundary
  // .*? - allow (optinally) any characters after
  val REGEX_FIND_WORD = ".*%s.*"
  val incapsulaRgx: Regex =  REGEX_FIND_WORD.format("ncapsula").r.unanchored
  // If incapsula incident detected and try to use HTMLUnit, then Sitelock captcha appears and HtmlUnit is just hanged
  // so detect incident ahead
  val incapsulaIncidentRgx: Regex =  REGEX_FIND_WORD.format(raw"Incapsula\sincident\sID").r.unanchored
  // standalone Sitelock captcha for case if it raises without Incapsula
  // Not sure but it seems Sitelock is using Google's reCAPTCHA
  val sitelockCaptchaRgx: Regex =  REGEX_FIND_WORD.format("Sitelock").r.unanchored
  val incapsulaWAFRgx: Regex =  REGEX_FIND_WORD.format(raw"content\.incapsula\.com").r.unanchored


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
    val mediaType = MediaType(TikaInstance.detect(url))
    logger.debug(s"Retrieving URL '$url' with MIME type '${Try(mediaType)}'")

    // switched to using `digest` only and never using `retrieveBinary` (issue #205)
    val futPage = digest(url).map(x => Page(x._2.bodyAsBytes.toArray))

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
    def loadFrame(frameElement: Element): Future[Element] = retrieve(url + frameElement.attr("src")).map { page =>
      frameElement.html(ByteString(page.content.toArray).utf8String)
    }

    // find all frames in framesets and load them
    val framesElems = docJsoup.getElementsByTag("frame").asScala.toIterator
    val loadedFrames = framesElems.map(loadFrame)

    // the data is all set into correct Elements in loadFrame method which takes Element instance as parameter
    // and changes data inside that element (lf.size is only used in ContentRetrieverTests)
    Future.sequence(loadedFrames).map(lf => (docJsoup.html(), lf.size))
  }

  val MAX_REDIRECTS = 8


  /**
    * This method is called if "incapsula" keyword found in page response
    * Soon new WAFs will be added to be detected
    * the method uses HtmlUnit testing tool which runs JavaScript scripts
    * and waits until all scripts are loaded, as well it runs loaded scripts
    * what provides necessary calculations to bypass WAF
    */
  //Todo add check for other WAFs
  def bypassWAF(url: String): Future[(String, WSResponse)] = {
    // these settings are important to run JavaScript in loaded page by emulated browser
    val webClient: WebClient = new WebClient(BrowserVersion.EDGE)
    webClient.setAjaxController(new AjaxController(){
      override def processSynchron(page: HtmlPage, request: WebRequest, async: Boolean) = true
    })

    // this low timeout is required because WAFs can include several redirects and
    // heavy JavaScripts to be loaded from Wordpress CMS sites (usually used with Incapsula plugin)
    // dependtly on client/server bandwidth or server capacity (Php sites usually take much resourses)
    webClient.waitForBackgroundJavaScript(20000)
    webClient.getOptions().setJavaScriptEnabled(true)
    webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
    webClient.getOptions().setThrowExceptionOnScriptError(false);
    webClient.getOptions().setCssEnabled(false)
    webClient.getOptions().setRedirectEnabled(true)
    webClient.getOptions().setUseInsecureSSL(true)

    // HtmlUnit throws too many non-critical exceptions
    // so it is commented now to the moment when new approach of retry for HtmlUnit loading scripts is considered
    // too many Exceptions probably thrown by HTMLUnit because of some scripts and html inconsistency
    // because Incapsula has also a plugin for Wordpress engine, which is a source of plenties of errors
    // which we test on by this url
    // http://www.arnoldkling.com/blog/does-america-over-incarcerate/
    def retryGetPage[T](n: Int)(fn: => T): T = {
         try {
          fn
         } catch {
           case e: FailingHttpStatusCodeException  if n > 1 =>
             if (n > 1) retryGetPage(n - 1)(fn)
             else{
               throw HtmlUnitFailingHttpStatusCodeException(e.getMessage)
             }
         }
    }
    val htmlPage: HtmlPage = retryGetPage[HtmlPage](3)(webClient.getPage(url))
    val html = htmlPage.asText()
    println(html) //Todo remove this line
    val contentByte = html.toCharArray.map(_.toByte)
    val response = new ResponseBuilder().accumulate(new HttpResponseBodyPart(true){
      override def getBodyPartBytes: Array[Byte] = contentByte
      override def length(): Int = contentByte.length
      override def getBodyByteBuffer: ByteBuffer = ByteBuffer.allocate(contentByte.length)
    }).build()

    Future.successful(url, AhcWSResponse(StandaloneAhcWSResponse(response)))
  }

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
            case _ => checkKnownProblems(url, res).map(_.getOrElse((url, res)))
          }
        }
      }
    }
    recget(link)
  }

  /** Detects known WAFs and captchas */
  //Todo optimized order of different WAFs and different Capthas regexis
  def checkKnownProblems(url: String, res: WSResponse): Future[Option[(String, WSResponse)]] = {
    res.body.contains("ncapsula") match  {
      //case s if s.matches(incapsulaRgx.toString())  => // Diabled because it is not working sometimes and passed to next regex case
      case true =>

        res.body match {
            // "Sitelock" captcha raised when incapsula suspects crawler
          case  s if s.matches(sitelockCaptchaRgx.toString()) =>
            throw CaptchaException ("SiteLock captcha detected")

            // "Incapsula incident" is raised if incapsula detected suspected behavior and blacklisted ip or pc, or something else
          case  s if s.matches(incapsulaIncidentRgx.toString()) =>
            throw IncapsulaCaptchaIncidentException ("Incapsula incident (Sitelock captcha detected")

          // if no problems detected except incapsula WAF raised => bypass WAF
          // html page with Incapsula scripts are small, usually < 1000 chars
          case s if s.contains("content.incapsula.com") =>
            bypassWAF(url).map(r => Some(r))

            // if no incident detected, no incapsula script detected and no incapsula captcha detected then
            // it's a probably word "Incapsula" in text jut process in regular way
          case s if !s.contains("content.incapsula.com") => Future.successful(Some(url, res))
        }
        // Todo add popular WAFs detection and invoke HTMLUnit to bypass detected WAF
        // Todo need to find finger prints of:
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

        // if no WAFs or captchas detected then just process representation
      case false =>
        Future.successful (Some(url, res) )
    }
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

/** This exception is throwed if captcha on webpage detected*/
case class CaptchaException(msg:String)  extends Exception(msg)
/** This exception is throwed if Incapsula blacklisted ip or pc, or blacklisted something else*/
case class IncapsulaCaptchaIncidentException(msg:String)  extends Exception(msg)

case class HtmlUnitFailingHttpStatusCodeException(msg:String)  extends Exception(msg)
