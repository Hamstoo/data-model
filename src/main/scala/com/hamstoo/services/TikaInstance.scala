package com.hamstoo.services

import org.apache.tika.Tika
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.{AutoDetectParser, ParseContext}
import org.apache.tika.sax.BodyContentHandler

/**
  * Singleton object for Tika.
  * Note that this singleton object extends a constructed Tika instance, not the Tika class.
  */
object TikaInstance extends Tika() {

  /** Common constructs used in more than one place. */
  def constructCommon() = (new BodyContentHandler(-1), new Metadata(), new ParseContext(), new AutoDetectParser())
}
