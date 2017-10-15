package com.hamstoo.services

import java.io.{ByteArrayInputStream, InputStream}
import javax.activation.MimeType

import com.hamstoo.utils.MediaTypeSupport
import org.apache.tika.Tika
import org.apache.tika.metadata.{Metadata, PDF, Property, TikaCoreProperties}
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.{AutoDetectParser, ParseContext, Parser}
import org.apache.tika.sax.BodyContentHandler

/**
  * Singleton object for Tika.
  */
object TikaInstance extends Tika() {

  /* gets <title> meta tag value from any file
     should accept Metadata instance of already parsed instance if caller includes one
     to avoid duplication of instantiation of heavy Tika objects and double parsing
     This method should accept either (titleTag + optMetadata) or (optByteArray)
   */
  def getTitle(
      titleProperty: Option[Property] = Option.empty[Property],
      optMetadata: Option[Metadata] = Option.empty[Metadata],
      optArrayBytes: Option[Array[Byte]] = Option.empty[Array[Byte]]): Option[String] = {

    // check if metadata is ready to extract else parse arrayByte
    optMetadata.fold(parseTitleMetaTag(optArrayBytes))(metadata => Option(metadata.get(titleProperty.get)))
  }

  /* parse ArrayByte if Metadata instance is not accessible from caller place
       this title metatag can also be empty so return None
    */
  def parseTitleMetaTag(otpByteArray : Option[Array[Byte]]): Option[String] = {
    otpByteArray.flatMap { byteArray =>

      val (parser, contentHandler, metadata, parseContext) = createCommonParameters

      parseContext.set(classOf[Parser], parser)
      val is: InputStream = new ByteArrayInputStream(byteArray)
      parser.parse(is, contentHandler, metadata, parseContext)
      val mimeType = new MimeType(detect(byteArray))
      val titleProperty = if (MediaTypeSupport.isPDF(mimeType)) {
        PDF.DOC_INFO_TITLE
      } else {
        TikaCoreProperties.TITLE
      }
      Option(metadata.get(titleProperty))
    }
  }

  // creates common parameters for Tika Parser constructor
  def createCommonParameters = {
    (new AutoDetectParser(),
    new BodyContentHandler(-1),
    new Metadata(),
    new ParseContext())
  }

}
