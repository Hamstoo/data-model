package com.hamstoo

import java.util.Locale

import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Call, Request}
import reactivemongo.api.BSONSerializationPack.Reader
import reactivemongo.api.collections.GenericQueryBuilder
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{CollectionIndexesManager, Index}
import reactivemongo.api._
import reactivemongo.bson.{BSONDocument, BSONElement, Producer}

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.{TraversableLike, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.matching.Regex
import scala.util.{Failure, Random, Success, Try}


package object utils {

  /** Singleton database driver (actor system) instance. */
  private var dbDriver: Option[MongoDriver] = None

  /** Initialize the singleton database driver instance. */
  def initDbDriver(): Unit = {
    if (dbDriver.isDefined)
      throw new Exception("Database driver already defined")
    Logger.info("Initializing database driver...")
    dbDriver = Some(MongoDriver())
    Logger.info("Done initializing database driver")
  }

  /** Close the singleton database driver instance. */
  def closeDbDriver(timeout: FiniteDuration = 2 seconds): Option[Boolean] = {
    val tri = dbDriver.map { d =>
      Logger.info("Closing database driver...")
      Try(d.close(timeout)) match { // `MongoDriver.close` calls Await.result (which can throw an exception)
        case Success(_) => Logger.info("Done closing database driver")
          true
        case Failure(t) => Logger.warn(s"Exception while attempting to close database driver; proceeding anyway", t)
          false
      }
    }
    dbDriver = None
    tri
  }

  /**
    * Construct database connection pool, which should only happen once (for a given URI) because it instantiates a
    * whole thread pool of connections.
    *
    * From the docs:
    *   "A MongoDriver instance manages the shared resources (e.g. the actor system for the asynchronous processing).
    *  A connection manages a pool of network channels. In general, a MongoDriver or a MongoConnection should not be
    *  instantiated more than once."
    *    [http://reactivemongo.org/releases/0.12/documentation/tutorial/connect-database.html]
    *
    * @param uri        The database server's URI.
    * @param nAttempts  The default database name.
    */
  @tailrec
  final def getDbConnection(uri: String, nAttempts: Int = 5): (MongoConnection, String) = {
    MongoConnection.parseURI(uri).map { parsedUri =>
      if (dbDriver.isEmpty)
        initDbDriver()
      // the below doesn't work bc/ the second parameter is used as the Akka actor name, which must be unique when testing
      //dbDriver.get.connection(parsedUri, parsedUri.db, strictUri = false).get
      Logger.info(s"Database name: ${parsedUri.db.get}")
      (dbDriver.get.connection(parsedUri), parsedUri.db.get)
    } match {
      case Success(conn) =>
        Logger.info(s"Established connection to MongoDB via URI: $uri")
        synchronized(wait(2000))
        conn
      case Failure(e) =>
        e.printStackTrace()
        synchronized(wait(1000))
        if (nAttempts == 0)
          throw new RuntimeException("Failed to establish connection to MongoDB; aborting", e)
        else {
          Logger.warn(s"Failed to establish connection to MongoDB; retrying (${nAttempts-1} attempts remaining)", e)
          getDbConnection(uri, nAttempts = 1)
        }
    }
  }

  /** Used by backend: AuthController and MarksController. */
  def endpoint2Link(endpoint: Call)(implicit request: Request[Any]): String = httpHost + endpoint
  def httpHost(implicit request: Request[Any]): String = s"http${if (request.secure) "s" else ""}://${request.host}"

  /** Extended ReactiveMongo QueryBuilder */
  implicit class ExtendedQB(private val qb: GenericQueryBuilder[BSONSerializationPack.type]) extends AnyVal {

    /**
      * Short for `.cursor` with `.collect` consecutive calls with default error handler. Or maybe this is
      * short for "collection" analogous to `GenericQueryBuilder.one`.  Either way, it works.
      */
    def coll[E, C[_] <: Iterable[_]](n: Int = -1)
                                    (implicit r: Reader[E], cbf: CanBuildFrom[C[_], E, C[E]]): Future[C[E]] = {

      // "In most cases, modifying the batch size will not affect the user or the application, as the mongo shell and
      // most drivers return results as if MongoDB returned a single batch."
      //   [https://docs.mongodb.com/manual/reference/method/cursor.batchSize/]
      qb/*.options(QueryOpts().batchSize(n))*/.cursor[E]().collect[C](n, Cursor.FailOnError[C[E]]())
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
      if (wr.ok) f else Future.failed(new Exception(wr.writeErrors.mkString("; ")))

    /**
      * Note that this only fails if there was a real error.  If 0 documents were updated that is not considered
      * an error.
      */
    def failIfError: Future[Unit] =
      if (wr.ok) Future.successful {} else Future.failed(new Exception(wr.writeErrors.mkString("; ")))
  }

  // MongoDB Binary Indexes have a max size of 1024 bytes.  So to combine a 12-char string with a byte array
  // as in the `urldups` collection index, the byte array must be, at most, 992 bytes.  This is presumably
  // due to some overhead in the MongoDB data types (BinData and String) and/or overhead due to the combination
  // of multiple fields in a single index.
  // From MongoMarksDao: `Index(USRPRFX -> Ascending :: URLPRFX -> Ascending :: Nil) % s"bin-$USRPRFX-1-$URLPRFX-1"`
  val URL_PREFIX_LENGTH = 992
  val URL_PREFIX_COMPLEMENT_LENGTH = 12

  /** Generate an ID to be used for a document in a database collection. */
  type ObjectId = String
  def generateDbId(length: Int): ObjectId = Random.alphanumeric.take(length).mkString

  // if a mark doesn't have a repr (either reprId is in NON_IDS or reprId can't be found in the database) then
  // use this as its eratingId
  val NO_REPR_ERATING_ID = "norepr"

  // if any of these strings are longer than Representation.ID_LENGTH it can cause a `marks` collection index to break,
  // MongoMarksDao.update[Public,Private]ReprId checks for this, see comment on com.hamstoo.utils.URL_PREFIX_LENGTH,
  // 2017-12-8 update - the offending index has since been removed
  val FAILED_REPR_ID = "failed"
  val TIMEOUT_REPR_ID = "timeout"
  val NONE_REPR_ID = "none"
  val CAPTCHA_REPR_ID = "captcha"
  val HTMLUNIT_REPR_ID = "htmlunit"
  val UPLOAD_REPR_ID = "upload"

  // set of IDs that aren't really IDs so that we can filter them out when searching for things with "true" IDs
  val NON_IDS = Set("", FAILED_REPR_ID, TIMEOUT_REPR_ID, NONE_REPR_ID, CAPTCHA_REPR_ID, HTMLUNIT_REPR_ID,
                    UPLOAD_REPR_ID, NO_REPR_ERATING_ID)

  /** Use the private ID when available, o/w use the public ID. */
  def reconcilePrivPub(priv: Option[String], pub: Option[String]): Option[String] =
    priv.filterNot(NON_IDS.contains).orElse(pub.filterNot(NON_IDS.contains)).orElse(priv).orElse(pub)
    //if (priv.exists(NON_IDS.contains) && pub.exists(!NON_IDS.contains(_))) pub.get else priv.orElse(pub)

  implicit class ExtendedString(private val s: String) extends AnyVal {
    /**
      * Retrieves first chars of a string as binary sequence. This method exists as a means of constructing
      * binary prefixes of string fields for binary indexes in MongoDB.
      */
    def binaryPrefix: mutable.WrappedArray[Byte] = s.getBytes.take(URL_PREFIX_LENGTH)

    def binPrfxComplement: String = s.take(URL_PREFIX_COMPLEMENT_LENGTH)
  }

  implicit class ExtendedBytes(private val ary: Array[Byte]) extends AnyVal {
    def binaryPrefix: mutable.WrappedArray[Byte] = ary.take(URL_PREFIX_LENGTH)
  }

  /**
    * This could probably be done more generically with Typeclasses or something, but this is quick and easy
    * and sufficient for now.
    * See also: https://www.cakesolutions.net/teamblogs/demystifying-implicits-and-typeclasses-in-scala
    */
  implicit class ExtendedDouble(private val d: Double) extends AnyVal {
    def coalesce(ifNaN: Double): Double = if (d.isNaN) ifNaN else d
    def coalesce0(implicit ev: Numeric[Double]): Double = coalesce(ev.zero) // using Numeric typeclass
    def or0: Double = coalesce0
  }

  /**
    * MongoDB documents with TimeThrus equal to this value are current.  Those with lesser TimeThrus were either
    * deleted or have been updated, in which case there should be a new document with a matching TimeFrom.
    *
    * For reference, Long.MaxValue is equal to 9223372036854775807 or MongoDB's `NumberLong("9223372036854775807")`.
    */
  type TimeStamp = Long
  type DurationMils = Long
  val INF_TIME: TimeStamp = Long.MaxValue
  def TIME_NOW: TimeStamp = DateTime.now.getMillis

  implicit class ExtendedTimeStamp(private val ms: TimeStamp) extends AnyVal {
    /** Converts from time in milliseconds to a Joda DateTime. */
    def dt: DateTime = new DateTime(ms, DateTimeZone.UTC)
    /** Giga-seconds make for an easily readable display. */
    def Gs: Double = ms.toDouble / 1000000000
    /** Time format. */
    def tfmt: String = s"${ms.dt} [${ms.Gs}]".replaceAll("T00:00:00.000", "").replaceAll(":00:00.000", "")
    /** Converts from time in milliseconds to a JsValueWrapper. */
    def toJson: Json.JsValueWrapper =
      s"${dt.year.getAsString}-${dt.monthOfYear.getAsString}-${dt.dayOfMonth.getAsString}"
  }

  implicit class ExtendedDurationMils(private val dur: DurationMils) extends AnyVal {
    def toDays: Double = dur.toDouble / 1000 / 60 / 60 / 24
    def toDuration: Duration = dur.millis
    /** Duration format. */
    def dfmt: String = s"${dur.toDays} days [${dur.Gs}]"
  }

  /** It seems unnecessary to muck up the type inheritance hierarchy for silly stuff like this. */
  implicit class IdAndTimeFromable(private val duck: { def id: ObjectId; def timeFrom: TimeStamp }) {
    import scala.language.reflectiveCalls
    def idt: String = s"${duck.id} [${duck.timeFrom}]"
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
    * words are all found independently: "can't", "can't", and "can't".
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

  /** Returns a string of memory statistics. */
  def memoryString: String =
    f"total: ${Runtime.getRuntime.totalMemory/1e6}%.0f, free: ${Runtime.getRuntime.freeMemory/1e6}%.0f"
}
