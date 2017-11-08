package com.hamstoo.services

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.parser.{AutoDetectParser, ParseContext}
import org.apache.tika.sax.BodyContentHandler

/**
  * Singleton object for Tika.
  * Note that this singleton object extends a constructed Tika instance, not the Tika class.
  */
object TikaInstance extends Tika() {

  /** Common constructs used in more than one place. */
  def constructCommon(enableOCR: Boolean = true) = (new BodyContentHandler(-1), new Metadata(), createContext(enableOCR), new AutoDetectParser())

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
