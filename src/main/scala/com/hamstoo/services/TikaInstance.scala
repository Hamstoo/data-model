package com.hamstoo.services

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.{AutoDetectParser, ParseContext, Parser}
import org.apache.tika.sax.BodyContentHandler
import play.api.Logger

/**
  * Singleton object for Tika.
  * Note that this singleton object extends a constructed Tika facade instance, not the Tika facade class itself.
  *
  * We don't even seem to really need to use the "Tika facade" but we extend it here to gain access to `detect`.
  * "The Tika facade, provides a number of very quick and easy ways to have your content parsed by Tika, and return
  * the resulting plain text. ... For more control, you can call the Tika Parsers directly."
  *   [https://tika.apache.org/1.16/examples.html]
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

  /** Common constructs needed each time we process a new file.  Tesseract OCR enabled by default. */
  def apply(): (BodyContentHandler, Metadata, ParseContext, Parser) = {

    // https://stackoverflow.com/questions/32354209/apache-tika-extract-scanned-pdf-files
    val parser = new AutoDetectParser
    val handler = new BodyContentHandler(-1 /*Integer.MAX_VALUE*/)

    val config = new TesseractOCRConfig
    val pdfConfig = new PDFParserConfig

    // "Beware: some PDF documents of modest size (~4MB) can contain thousands of embedded images totaling > 2.5 GB.
    // Also, at least as of PDFBox 1.8.5, there can be surprisingly large memory consumption and/or out of memory
    // errors. Set to true with caution."
    //   [https://tika.apache.org/1.16/api/org/apache/tika/parser/pdf/PDFParserConfig.html]
    // https://github.com/Norconex/collector-http/issues/267
    pdfConfig.setExtractInlineImages(true)

    val parseContext = new ParseContext
    parseContext.set(classOf[TesseractOCRConfig], config)
    parseContext.set(classOf[PDFParserConfig], pdfConfig)
    parseContext.set(classOf[Parser], parser) // "need to add this to make sure recursive parsing happens!"

    (handler, new Metadata(), parseContext, parser)
  }
}
