package com.hamstoo.utils

import javax.activation.MimeType

/**
  * Enumeration of various media/MIME types.
  */
object MediaType /*extends Enumeration*/ {

  // TODO: use Guava.MediaType instead of these strings?
  //   https://stackoverflow.com/questions/7904497/is-there-an-enum-with-mime-types-in-java

  // HTML
  lazy val TEXT_HTML = new MimeType("text/html")
  lazy val APPLICATION_OCTET_STREAM = new MimeType("application/octet-stream")
  lazy val TEXT_X_PHP = new MimeType("text/x-php")
  lazy val APPLICATION_XHTML_XML = new MimeType("application/xhtml+xml")

  // PDF
  lazy val APPLICATION_PDF = new MimeType("application/pdf")

  // media
  lazy val AUDIO_ANY = new MimeType("audio/*")
  lazy val MP3 = new MimeType("audio/mp3")
  lazy val MP4 = new MimeType("audio/mp4")
  lazy val OGG = new MimeType("audio/ogg")
  lazy val AVI = new MimeType("video/avi")
  lazy val MPEG_AUDIO = new MimeType("audio/mpeg")
  lazy val MP4_VIDEO = new MimeType("video", "mp4")
  lazy val MPEG_VIDEO = new MimeType("video", "mpeg")
  lazy val OGG_VIDEO = new MimeType("video", "ogg")
  lazy val QUICKTIME = new MimeType("video", "quicktime")
  lazy val WEBM_VIDEO = new MimeType("video", "webm")
  lazy val WMV = new MimeType("video", "x-ms-wmv")
  lazy val BMP = new MimeType("image/bmp")
  lazy val CRW = new MimeType("image/x-canon-crw")
  lazy val GIF = new MimeType("image/gif")
  lazy val ICO = new MimeType("image/vnd.microsoft.icon")
  lazy val JPEG = new MimeType("image/jpeg")
  lazy val PNG = new MimeType("image/png")
  lazy val PSD = new MimeType("image/vnd.adobe.photoshop")
  lazy val SVG_UTF_8 = new MimeType("image/svg+xml")
  lazy val TIFF = new MimeType("image/tiff")
  lazy val WEBP = new MimeType("image/webp")

  // text
  lazy val TEXT_ANY = new MimeType("text/*")
  lazy val CSV = new MimeType("text/x-csv")
  lazy val PLAIN = new MimeType("text/plain")
  lazy val XML = new MimeType("text/xml")
  lazy val DOC = new MimeType("application/msword")
  lazy val DOCX = new MimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
  lazy val DOTX = new MimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.template")
  lazy val DOCM = new MimeType("application/vnd.ms-word.document.macroEnabled")
  lazy val XLS = new MimeType("application/vnd.ms-excel")
  lazy val XLSX = new MimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  lazy val XLTX = new MimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.template")
  lazy val XLSM = new MimeType("application/vnd.ms-excel.sheet.macroEnabled")
  lazy val XLTM = new MimeType(" application/vnd.ms-excel.template.macroEnabled")
  lazy val XLAM = new MimeType("application/vnd.ms-excel.addin.macroEnabled")
  lazy val XLSB = new MimeType("application/vnd.ms-excel.sheet.binary.macroEnabled")
  lazy val PPT = new MimeType("application/vnd.ms-powerpoint")
  lazy val PPTX = new MimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation")
  lazy val POTX = new MimeType("application/vnd.openxmlformats-officedocument.presentationml.template")
  lazy val PPSX = new MimeType("application/vnd.openxmlformats-officedocument.presentationml.slideshow")
  lazy val PPAM = new MimeType("application/vnd.ms-powerpoint.addin.macroEnabled")
  lazy val PPTM = new MimeType("application/vnd.ms-powerpoint.presentation.macroEnabled")
  lazy val POTM = new MimeType("application/vnd.ms-powerpoint.template.macroEnabled")
  lazy val PPSM = new MimeType("application/vnd.ms-powerpoint.slideshow.macroEnabled")
  lazy val OCTET_STREAM = new MimeType("application/octet-stream");
  lazy val OOXML_DOCUMENT = new MimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  lazy val OOXML_PRESENTATION = new MimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
  lazy val OOXML_SHEET = new MimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  lazy val OPENDOCUMENT_GRAPHICS = new MimeType("application/vnd.oasis.opendocument.graphics");
  lazy val OPENDOCUMENT_PRESENTATION = new MimeType("application/vnd.oasis.opendocument.presentation");
  lazy val OPENDOCUMENT_SPREADSHEET = new MimeType("application/vnd.oasis.opendocument.spreadsheet");
  lazy val OPENDOCUMENT_TEXT = new MimeType("application/vnd.oasis.opendocument.text");
  lazy val POSTSCRIPT = new MimeType("application/postscript");
  lazy val RDF_XML_UTF_8 = new MimeType("application/rdf+xml")
  lazy val RTF_UTF_8 = new MimeType("application/rtf")
  lazy val EPUB = new MimeType("application/epub+zip")
  lazy val ODT = new MimeType("application/vnd.oasis.opendocument.text")
  lazy val ODTT = new MimeType("application/vnd.oasis.opendocument.text-template")
  lazy val ODTTW = new MimeType("application/vnd.oasis.opendocument.text-web")
  lazy val ODTHTML = new MimeType("application/vnd.oasis.opendocument.text-master")
  lazy val ODTG = new MimeType("application/vnd.oasis.opendocument.graphics")
  lazy val ODTD = new MimeType("application/vnd.oasis.opendocument.graphics-template")
  lazy val ODTP = new MimeType("application/vnd.oasis.opendocument.presentation")
  lazy val ODTPT = new MimeType("application/vnd.oasis.opendocument.presentation-template")
  lazy val ODTS = new MimeType("application/vnd.oasis.opendocument.spreadsheet")
  lazy val ODTST = new MimeType("application/vnd.oasis.opendocument.spreadsheet-template")
  lazy val ODTCH = new MimeType("application/vnd.oasis.opendocument.chart")
  lazy val ODTFOR = new MimeType("application/vnd.oasis.opendocument.formula")
  lazy val ODTDB = new MimeType("application/vnd.oasis.opendocument.database")
  lazy val ODTDI = new MimeType("application/vnd.oasis.opendocument.image")
  lazy val ODTEXT = new MimeType("application/vnd.openofficeorg.extension")
  lazy val SUNWR = new MimeType("application/vnd.sun.xml.writer")
  lazy val SUNWRT = new MimeType("application/vnd.sun.xml.writer.template")
  lazy val SUNCALC = new MimeType("application/vnd.sun.xml.calc")
  lazy val SUNCT = new MimeType("application/vnd.sun.xml.calc.template")
  lazy val SUNDR = new MimeType("application/vnd.sun.xml.draw")
  lazy val SUNDRT = new MimeType("application/vnd.sun.xml.draw.template")
  lazy val SUNIMPR = new MimeType("application/vnd.sun.xml.impress")
  lazy val SUNIMPRT = new MimeType("application/vnd.sun.xml.impress.template")
  lazy val SUNWRGL = new MimeType("application/vnd.sun.xml.writer.global")
  lazy val SUNMATH = new MimeType("application/vnd.sun.xml.math")
  lazy val STARWR = new MimeType("application/vnd.stardivision.writer")
  lazy val STARWRGL = new MimeType("application/vnd.stardivision.writer-global")
  lazy val STARCALC = new MimeType("application/vnd.stardivision.calc")
  lazy val STARDR = new MimeType("application/vnd.stardivision.draw")
  lazy val STAROMPR = new MimeType("application/vnd.stardivision.impress")
  lazy val STARIMPRPAC = new MimeType("application/vnd.stardivision.impress-packed")
  lazy val STARMATH = new MimeType("application/vnd.stardivision.math")
  lazy val STARCH = new MimeType("application/vnd.stardivision.chart")
  lazy val STARMAIL = new MimeType("application/vnd.stardivision.mail")
  lazy val XSTARWR = new MimeType("application/x-starwriter")
  lazy val XSTARCALC = new MimeType("application/x-starcalc")
  lazy val XSTARDR = new MimeType("application/x-stardraw")
  lazy val XSTARIMPR = new MimeType("application/x-starimpress")
  lazy val XSTARMATH = new MimeType("application/x-starmath")
  lazy val XSTARCH = new MimeType("application/x-starchart")
  lazy val KEYNOTE = new MimeType("application/x-iwork-keynote-sffkey")
  lazy val PAGES = new MimeType("application/x-iwork-pages-sffpages")
  lazy val NUMBERS = new MimeType("application/x-iwork-numbers-sffnumbers")
}
