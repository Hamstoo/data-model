package com.hamstoo.utils

import javax.activation.MimeType

/**
  * MimeType unfortunately doesn't implement `equals`.
  * https://stackoverflow.com/questions/43110074/why-does-javax-mimetype-not-implement-equals
  */
class MediaType(str: String) extends MimeType(str) {

  /** https://alvinalexander.com/scala/how-to-define-equals-hashcode-methods-in-scala-object-equality */
  def canEqual(a: Any): Boolean = a.isInstanceOf[MediaType]
  override def equals(that: Any): Boolean =
    that match {
      case that: MediaType => that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  /** Just use toString's hashCode. */
  override def hashCode: Int = super.toString.hashCode
}

/**
  * Enumeration of various media/MIME types.
  */
object MediaType /*extends Enumeration*/ {

  // TODO: use Guava.MediaType instead of these strings?
  //   https://stackoverflow.com/questions/7904497/is-there-an-enum-with-mime-types-in-java

  /** Convenience method to construct a MediaType from a string. */
  def apply(strMimeType: String): MediaType = new MediaType(strMimeType)

  // slightly overriding the idea of a MediaType here to accommodate repr-engine's RepresentationActor serviceProcess
  // method which switches its behavior based on a MediaType in order to choose an appropriate RepresentationService
  lazy val USER_DATA = MediaType("user/data")

  // HTML
  lazy val TEXT_HTML = MediaType("text/html")
  lazy val APPLICATION_OCTET_STREAM = MediaType("application/octet-stream")
  lazy val TEXT_X_PHP = MediaType("text/x-php")
  lazy val APPLICATION_XHTML_XML = MediaType("application/xhtml+xml")

  // PDF
  lazy val APPLICATION_PDF = MediaType("application/pdf")

  // media
  lazy val AUDIO_ANY = MediaType("audio/*")
  lazy val MP3 = MediaType("audio/mp3")
  lazy val MP4 = MediaType("audio/mp4")
  lazy val OGG = MediaType("audio/ogg")
  lazy val AVI = MediaType("video/avi")
  lazy val MPEG_AUDIO = MediaType("audio/mpeg")
  lazy val MP4_VIDEO = MediaType("video/mp4")
  lazy val MPEG_VIDEO = MediaType("video/mpeg")
  lazy val OGG_VIDEO = MediaType("video/ogg")
  lazy val QUICKTIME = MediaType("video/quicktime")
  lazy val WEBM_VIDEO = MediaType("video/webm")
  lazy val WMV = MediaType("video/x-ms-wmv")
  lazy val BMP = MediaType("image/bmp")
  lazy val CRW = MediaType("image/x-canon-crw")
  lazy val GIF = MediaType("image/gif")
  lazy val ICO = MediaType("image/vnd.microsoft.icon")
  lazy val JPEG = MediaType("image/jpeg")
  lazy val PNG = MediaType("image/png")
  lazy val PSD = MediaType("image/vnd.adobe.photoshop")
  lazy val SVG_UTF_8 = MediaType("image/svg+xml")
  lazy val TIFF = MediaType("image/tiff")
  lazy val WEBP = MediaType("image/webp")

  // text
  lazy val TEXT_ANY = MediaType("text/*")
  lazy val CSV = MediaType("text/x-csv")
  lazy val PLAIN = MediaType("text/plain")
  lazy val XML = MediaType("text/xml")
  lazy val DOC = MediaType("application/msword")
  lazy val DOCX = MediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
  lazy val DOTX = MediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.template")
  lazy val DOCM = MediaType("application/vnd.ms-word.document.macroEnabled")
  lazy val XLS = MediaType("application/vnd.ms-excel")
  lazy val XLSX = MediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  lazy val XLTX = MediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.template")
  lazy val XLSM = MediaType("application/vnd.ms-excel.sheet.macroEnabled")
  lazy val XLTM = MediaType(" application/vnd.ms-excel.template.macroEnabled")
  lazy val XLAM = MediaType("application/vnd.ms-excel.addin.macroEnabled")
  lazy val XLSB = MediaType("application/vnd.ms-excel.sheet.binary.macroEnabled")
  lazy val PPT = MediaType("application/vnd.ms-powerpoint")
  lazy val PPTX = MediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation")
  lazy val POTX = MediaType("application/vnd.openxmlformats-officedocument.presentationml.template")
  lazy val PPSX = MediaType("application/vnd.openxmlformats-officedocument.presentationml.slideshow")
  lazy val PPAM = MediaType("application/vnd.ms-powerpoint.addin.macroEnabled")
  lazy val PPTM = MediaType("application/vnd.ms-powerpoint.presentation.macroEnabled")
  lazy val POTM = MediaType("application/vnd.ms-powerpoint.template.macroEnabled")
  lazy val PPSM = MediaType("application/vnd.ms-powerpoint.slideshow.macroEnabled")
  lazy val OCTET_STREAM = MediaType("application/octet-stream");
  lazy val OOXML_DOCUMENT = MediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  lazy val OOXML_PRESENTATION = MediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
  lazy val OOXML_SHEET = MediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  lazy val OPENDOCUMENT_GRAPHICS = MediaType("application/vnd.oasis.opendocument.graphics");
  lazy val OPENDOCUMENT_PRESENTATION = MediaType("application/vnd.oasis.opendocument.presentation");
  lazy val OPENDOCUMENT_SPREADSHEET = MediaType("application/vnd.oasis.opendocument.spreadsheet");
  lazy val OPENDOCUMENT_TEXT = MediaType("application/vnd.oasis.opendocument.text");
  lazy val POSTSCRIPT = MediaType("application/postscript");
  lazy val RDF_XML_UTF_8 = MediaType("application/rdf+xml")
  lazy val RTF_UTF_8 = MediaType("application/rtf")
  lazy val EPUB = MediaType("application/epub+zip")
  lazy val ODT = MediaType("application/vnd.oasis.opendocument.text")
  lazy val ODTT = MediaType("application/vnd.oasis.opendocument.text-template")
  lazy val ODTTW = MediaType("application/vnd.oasis.opendocument.text-web")
  lazy val ODTHTML = MediaType("application/vnd.oasis.opendocument.text-master")
  lazy val ODTG = MediaType("application/vnd.oasis.opendocument.graphics")
  lazy val ODTD = MediaType("application/vnd.oasis.opendocument.graphics-template")
  lazy val ODTP = MediaType("application/vnd.oasis.opendocument.presentation")
  lazy val ODTPT = MediaType("application/vnd.oasis.opendocument.presentation-template")
  lazy val ODTS = MediaType("application/vnd.oasis.opendocument.spreadsheet")
  lazy val ODTST = MediaType("application/vnd.oasis.opendocument.spreadsheet-template")
  lazy val ODTCH = MediaType("application/vnd.oasis.opendocument.chart")
  lazy val ODTFOR = MediaType("application/vnd.oasis.opendocument.formula")
  lazy val ODTDB = MediaType("application/vnd.oasis.opendocument.database")
  lazy val ODTDI = MediaType("application/vnd.oasis.opendocument.image")
  lazy val ODTEXT = MediaType("application/vnd.openofficeorg.extension")
  lazy val SUNWR = MediaType("application/vnd.sun.xml.writer")
  lazy val SUNWRT = MediaType("application/vnd.sun.xml.writer.template")
  lazy val SUNCALC = MediaType("application/vnd.sun.xml.calc")
  lazy val SUNCT = MediaType("application/vnd.sun.xml.calc.template")
  lazy val SUNDR = MediaType("application/vnd.sun.xml.draw")
  lazy val SUNDRT = MediaType("application/vnd.sun.xml.draw.template")
  lazy val SUNIMPR = MediaType("application/vnd.sun.xml.impress")
  lazy val SUNIMPRT = MediaType("application/vnd.sun.xml.impress.template")
  lazy val SUNWRGL = MediaType("application/vnd.sun.xml.writer.global")
  lazy val SUNMATH = MediaType("application/vnd.sun.xml.math")
  lazy val STARWR = MediaType("application/vnd.stardivision.writer")
  lazy val STARWRGL = MediaType("application/vnd.stardivision.writer-global")
  lazy val STARCALC = MediaType("application/vnd.stardivision.calc")
  lazy val STARDR = MediaType("application/vnd.stardivision.draw")
  lazy val STAROMPR = MediaType("application/vnd.stardivision.impress")
  lazy val STARIMPRPAC = MediaType("application/vnd.stardivision.impress-packed")
  lazy val STARMATH = MediaType("application/vnd.stardivision.math")
  lazy val STARCH = MediaType("application/vnd.stardivision.chart")
  lazy val STARMAIL = MediaType("application/vnd.stardivision.mail")
  lazy val XSTARWR = MediaType("application/x-starwriter")
  lazy val XSTARCALC = MediaType("application/x-starcalc")
  lazy val XSTARDR = MediaType("application/x-stardraw")
  lazy val XSTARIMPR = MediaType("application/x-starimpress")
  lazy val XSTARMATH = MediaType("application/x-starmath")
  lazy val XSTARCH = MediaType("application/x-starchart")
  lazy val KEYNOTE = MediaType("application/x-iwork-keynote-sffkey")
  lazy val PAGES = MediaType("application/x-iwork-pages-sffpages")
  lazy val NUMBERS = MediaType("application/x-iwork-numbers-sffnumbers")
}
