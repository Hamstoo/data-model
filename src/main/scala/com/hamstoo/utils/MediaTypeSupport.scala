package com.hamstoo.utils


import javax.activation.MimeType


/**
  * Some handy sets of MIME types that we handle differently under different circumstances.
  */
object MediaTypeSupport {

  import MediaType._

  val HTMLMimeTypes: Seq[MimeType] = TEXT_HTML ::
    APPLICATION_OCTET_STREAM ::
    TEXT_X_PHP ::
    APPLICATION_XHTML_XML ::
    Nil

  val PDFMimeTypes: Seq[MimeType] = APPLICATION_PDF ::
    Nil

  // TODO: complete list, issue #121
  val MediaMimeTypes: Seq[MimeType] = AUDIO_ANY ::
    MP3 ::
    MP4 ::
    OGG ::
    AVI ::
    MPEG_AUDIO ::
    MP4_VIDEO ::
    MPEG_VIDEO ::
    OGG_VIDEO ::
    QUICKTIME ::
    WEBM_VIDEO ::
    WMV ::
    BMP ::
    CRW ::
    GIF ::
    ICO ::
    JPEG ::
    PNG ::
    PSD ::
    SVG_UTF_8 ::
    TIFF ::
    WEBP ::
    Nil

  val TextMimeTypes: Seq[MimeType] = TEXT_ANY :: CSV :: PLAIN :: XML :: DOC :: DOCX :: DOTX :: XLS ::
    XLSX :: XLTX :: XLSM :: XLTM :: XLAM :: XLSB :: PPT :: PPTX :: PPSX :: POTX :: PPTM :: PPAM ::
    POTM :: PPSM :: OCTET_STREAM :: OOXML_DOCUMENT :: OOXML_PRESENTATION :: OOXML_SHEET :: OPENDOCUMENT_GRAPHICS ::
    OPENDOCUMENT_GRAPHICS :: OPENDOCUMENT_PRESENTATION :: OPENDOCUMENT_SPREADSHEET :: OPENDOCUMENT_TEXT ::
    POSTSCRIPT :: RDF_XML_UTF_8 :: RDF_XML_UTF_8 :: RTF_UTF_8 :: EPUB :: ODT :: ODTT :: ODTTW :: ODTHTML ::
    ODTG :: ODTG :: ODTD :: ODTP :: ODTPT :: ODTS :: ODTST :: ODTCH :: ODTFOR :: ODTDB :: ODTDI :: ODTEXT ::
    SUNWR :: SUNWRT :: SUNCALC :: SUNCT :: SUNDR :: SUNDRT :: SUNIMPR :: SUNIMPRT :: SUNWRGL :: SUNMATH ::
    STARWR :: STARWRGL :: STARCALC :: STARDR :: STAROMPR :: STARIMPRPAC :: XSTARWR :: XSTARCALC :: XSTARDR ::
    XSTARIMPR :: XSTARMATH :: XSTARCH :: KEYNOTE :: PAGES :: NUMBERS ::
    Nil

  private def isMimeType(mimeTypes: Seq[MimeType])(mt: MimeType): Boolean = mimeTypes.exists(_ `match` mt)
  def isHTML(mt: MimeType): Boolean = isMimeType(HTMLMimeTypes)(mt)
  def isPDF(mt: MimeType): Boolean = isMimeType(PDFMimeTypes)(mt)

  def isText(mt: MimeType): Boolean =
    mt.getPrimaryType == "text" || isMimeType(TextMimeTypes)(mt)

  // TODO: this was changed from _.contains(mt.getPrimaryType) to mt.getPrimaryType.contains(_), please confirm correct
  // TODO: it was also changed from a long complicated !.map.filter.isEmpty expression to `exists`, also please confirm
  // TODO: it was also changed to use a Seq rather than a List (in this case they both give the same thing, and there's no reason to choose an explicit implementation of Seq)
  def isMedia(mt: MimeType): Boolean =
  Seq("audio", "video", "image").exists(mt.getPrimaryType.contains(_)) || isMimeType(MediaMimeTypes)(mt)
}
