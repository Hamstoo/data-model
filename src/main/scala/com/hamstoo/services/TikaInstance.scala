package com.hamstoo.services

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.{AutoDetectParser, ParseContext}
import org.apache.tika.sax.BodyContentHandler
import play.api.Logger

/**
  * Singleton object for Tika.
  * Note that this singleton object extends a constructed Tika instance, not the Tika class.
  */
object TikaInstance extends Tika() {

  val logger = Logger(TikaInstance.getClass)

  // this directory is used for storing fonts for OCR purposes, set the property to avoid the following error
  // "2017-11-07 19:20:42,734 [WARN] o.a.p.p.f.FileSystemFontProvider - You can assign a directory to the
  //   'pdfbox.fontcache' property java.io.FileNotFoundException: /usr/sbin/.pdfbox.cache (Permission denied)"
  val propertyName = "pdfbox.fontcache"
  logger.info(s"$propertyName = " +
    Option(System.getProperty(propertyName)).getOrElse {
      val default = "/tmp"
      System.setProperty(propertyName, default)
      default
    }
  )

  /** Common constructs used in more than one place. */
  def constructCommon(enableOCR: Boolean = true) =
    (new BodyContentHandler(-1), new Metadata(), createContext(enableOCR), new AutoDetectParser())

  /** Enables OCR, enabled by default*/
  def createContext(enableOCR: Boolean): ParseContext = {
   val parseContext = new ParseContext()

    if (enableOCR) {
      val config: TesseractOCRConfig = new TesseractOCRConfig()
      val pdfConfig: PDFParserConfig = new PDFParserConfig()
      pdfConfig.setExtractInlineImages(true)
      parseContext.set(classOf[TesseractOCRConfig], config)
      parseContext.set(classOf[PDFParserConfig], pdfConfig)
    }

    parseContext
  }
}
