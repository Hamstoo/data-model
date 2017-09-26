package com.hamstoo

import java.util.Locale

import org.joda.time.DateTime
import play.api.mvc.{Call, Request}
import reactivemongo.api.BSONSerializationPack.Reader
import reactivemongo.api.collections.GenericQueryBuilder
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{CollectionIndexesManager, Index}
import reactivemongo.api.{BSONSerializationPack, Cursor}
import reactivemongo.bson.{BSONDocument, BSONElement, Producer}

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.higherKinds
import scala.util.{Random, Try}
import scala.util.matching.Regex


package object utils {

  /** */
  def createLink(endpoint: Call)(implicit request: Request[Any]): String =
    s"${if (request.secure) "https" else "http"}://${request.host}$endpoint"

  implicit class ExtendedQB(private val qb: GenericQueryBuilder[BSONSerializationPack.type]) extends AnyVal {
    /** Short for `.cursor` with `.collect` consecutive calls with default error handler. */
    def coll[E, C[_] <: Iterable[_]](n: Int = -1)
                                    (implicit r: Reader[E], cbf: CanBuildFrom[C[_], E, C[E]]): Future[C[E]] = {
      qb.cursor[E]().collect[C](n, Cursor.FailOnError[C[E]]())
    }
  }

  implicit class ExtendedIndex(private val i: Index) extends AnyVal {
    /** */
    def %(name: String): (String, Index) = name -> i.copy(name = Some(name))
  }

  implicit class ExtendedIM(private val im: CollectionIndexesManager) extends AnyVal {
    /** */
    def ensure(indxs: Map[String, Index]): Unit = for (is <- im.list) {
      val exIs = is.flatMap(_.name).toSet
      exIs -- indxs.keySet - "_id_" foreach im.drop
      indxs.keySet -- exIs foreach { n => im.ensure(indxs(n)) }
    }
  }

  implicit class ExtendedWriteResult[R <: WriteResult](private val wr: R) extends AnyVal {
    /**
      * Function to be used in Future for-comprehensions or when a Future needs to be
      * Checks reactivemongo's update functions result for errors and returns a new future, failed if errors
      * encountered.
      */
    def ifOk[T](f: => Future[T]): Future[T] =
      if (wr.ok) f else Future failed new Exception(wr.writeErrors mkString "; ")

    def failIfError: Future[Unit] =
      if (wr.ok) Future successful {} else Future failed new Exception(wr.writeErrors mkString "; ")
  }

  // MongoDB `binary` indexes have a max size of 1024 bytes.  So to combine a 12-char ID with a byte array
  // as in the below `marks` collection index, the byte array must be, at most, 992 bytes.  This is presumably
  // due to some overhead in the MongoDB data types (BinData and String) and/or overhead due to the combination
  // of multiple fields in a single index.
  // From MongoMarksDao: `Index(UPRFX -> Ascending :: PUBREPR -> Ascending :: Nil) % s"bin-$UPRFX-1-$PUBREPR-1"`
  val URL_PREFIX_LENGTH = 992

  /** Generate an ID to be used for a document in a database collection. */
  def generateDbId(length: Int): String = Random.alphanumeric take length mkString

  implicit class ExtendedString(private val s: String) extends AnyVal {
    /**
      * Retrieves first chars of a string as binary sequence. This method exists as a means of constructing
      * binary prefixes of string fields for binary indexes in MongoDB.
      */
    def binaryPrefix: mutable.WrappedArray[Byte] = s.getBytes take URL_PREFIX_LENGTH
  }

  /**
    * MongoDB documents with TimeThrus equal to this value are current.  Those with lesser TimeThrus were either
    * deleted or have been updated, in which case there should be a new document with a matching TimeFrom.
    *
    * For reference, Long.MaxValue is equal to 9223372036854775807.
    */
  val INF_TIME: Long = Long.MaxValue

  implicit class ExtendedLong(private val ms: Long) extends AnyVal {
    /** Converts from time in milliseconds to a Joda DateTime. */
    def dt: DateTime = new DateTime(ms)
  }

  /** A couple of handy ReactiveMongo shortcuts that were formerly being defined in every DAO class. */
  val d = BSONDocument.empty
  val curnt: Producer[BSONElement] = com.hamstoo.models.Mark.TIMETHRU -> INF_TIME

  /** A couple regexes used in `parse` but that which may also be useful elsewhere. */
  val repeatedSpaceRgx: Regex = raw"\s{2,}".r.unanchored
  val crlftRgx: Regex = raw"[\n\r\t]".r.unanchored

  /**
    * Mild string parsing.  Nothing too severe here as these parsed strings are what are stored in the database
    * as representations.  In particular, these strings should include punctuation for a few reasons: (1) we may
    * want to display excerpts of them on the My Marks page in human-readable form, (2) we may want to use them
    * as input to our own fit of word2vec or GloVe, and (3) the Conceptnet Numberbatch model is fitted with them
    * included.  This last point is part of what leads to a 418,000-word English vocabulary in which the following
    * words are all found independently: "can't", "can't", and "can`t".
    */
  def parse(s: String): String = repeatedSpaceRgx.replaceAllIn(crlftRgx.replaceAllIn(s, " "), " ").trim

  /**
    * `parse` should've already been applied, but use \s+ anyway, just to be safe.  Lowercase'izing to make
    * the caching more efficient (TODO: https://github.com/Hamstoo/hamstoo/issues/68).
    */
  def tokenize(text: String): Seq[String] = text.toLowerCase(Locale.ENGLISH).split(raw"\s+")

  /**
    * Call it what you will: `try-with-resources` (Java), `using` (C#), `with` Python.
    * https://www.phdata.io/try-with-resources-in-scala/
    * https://stackoverflow.com/questions/2395984/scala-using-function
    * https://stackoverflow.com/questions/3241101/with-statement-equivalent-for-scala
    */
  def cleanly[A, B](resource: A)(cleanup: A => Unit)(doWork: A => B): Try[B] = {
    // i believe as this method's output is never consumed it's easier to just use the code below each time
    val t = Try(doWork(resource))
    Try(cleanup(resource))
    if (t.isFailure) println(t.failed.get)
    t
  }
}
