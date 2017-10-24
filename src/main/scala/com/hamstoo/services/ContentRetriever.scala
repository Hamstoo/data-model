package com.hamstoo.services

import java.net.{URI, URL}
import javax.activation.MimeType

import com.hamstoo.models.Page
import org.apache.tika.io.IOUtils
import play.api.Logger
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * ContentRetriever companion class.
  */
object ContentRetriever {
  val logger = Logger(classOf[ContentRetriever])

  /** Make sure the provided String is an absolute link. */
  def checkLink(s: String): String = if (s.isEmpty) s else new URI(s) match {
    case uri if uri.isAbsolute => uri.toASCIIString
    case uri => "http://" + uri.toASCIIString
  }

  /** Moved from hamstoo repo LinkageService class.  Used to take a MarkData as input and produce one as output. */
  def fixLink(url: String): String = Try(checkLink(url)).getOrElse("http://" + url)
}

/**
  * Tika-supported implementation of ContentRetriever.
  * All it does is `retrieve: String => Future[(MimeType, String)]`.
  */
class ContentRetriever(httpClient: WSClient)(implicit ec: ExecutionContext) {

  import ContentRetriever._

  /** Retrieve mime type and content (e.g. HTML) given a URL. */
  def retrieve(url: String): Future[Page] = {
    logger.debug(s"Retrieving URL $url with MIME type ${new MimeType(TikaInstance.detect(url))}")
    // TODO: it doesn't seem like we need to check for type here if everything can just be gotten as
    retrieveBinary(url).map(Page(_))
      /*.recoverWith {
        case e =>
          logger.warn(s"ContentRetriever.retrieve exception: ${e.getMessage}")
          retrieveHTML(url).map(x => Page(x.getBytes))
      }*/
      .recoverWith {
        case e =>
          logger.warn(s"ContentRetriever.retrieve exception: ${e.getMessage}")
          digest(url).map(x => Page(x._2.bodyAsBytes.toArray))
      }
  }

  /** This code was formerly part of the hamstoo repo's LinkageService. */
  def digest(url: String): Future[(String, WSResponse)] = {
    val link: String = checkLink(url)

    //@tailrec
    def recget(url: String, depth: Int = 1): Future[(String, WSResponse)] = {
      if (depth >= 5)
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
                  //Logger.info(s"Following redirect: ${response.status} newUrl = $newUrl, response.headers = ${response.headers}") // Logger.debug doesn't work
                  recget(newUrl, depth + 1)
                case _ =>
                  //Logger.info(s"Not following redirect: ${response.status} url = $url, response.headers = ${response.headers}") // Logger.debug doesn't work
                  Future.successful((url, res))
              }
            case _ =>
              //Logger.info(s"No redirect: ${response.status} url = $url, response.headers = ${response.headers}, response.body = ${response.body.take(100)}") // Logger.debug doesn't work
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
    * This may be related to the following log message:
    *   2017-10-23 08:45:46,532 [�[33mwarn�[0m] a.a.ActorSystemImpl - Sending an 2xx 'early' response before end of
    *   request was received... Note that the connection will be closed after this response. Also, many clients will
    *   not read early responses! Consider only issuing this response after the request data has been completely read!
    */
  private def retrieveBinary(url: String): Future[Array[Byte]] = Future {
    // PDFs do not have page source that can be represented as text, so
    // to save text in appropriate encoding it will be required to use Apache Tika
    // deeply here to detect encoding of document
    //   http://tika.apache.org/1.5/api/org/apache/tika/detect/EncodingDetector.html
    // Below idea is the example but I am not sure if it is good because PDF might consist of
    // scanned images or can consist images inside text.
    val is = new URL(url).openStream()
    //val encDet = new Icu4jEncodingDetector
    //val metadata = new Metadata()
    //val encodingCharset = encDet.detect(is, metadata)
    val byteArray = IOUtils.toByteArray(is)
    //val text = ByteString(byteArray).decodeString(encodingCharset)
    byteArray
  }
}
