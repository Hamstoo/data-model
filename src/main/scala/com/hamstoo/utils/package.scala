/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo

import java.util.Locale

import breeze.linalg.{DenseMatrix, DenseVector, svd}
import com.hamstoo.models.Representation.Vec
import monix.execution.Scheduler
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc.{Call, Request}
import reactivemongo.api.BSONSerializationPack.Reader
import reactivemongo.api.collections.GenericQueryBuilder
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{CollectionIndexesManager, Index}
import reactivemongo.api._
import reactivemongo.bson.{BSONDocument, BSONElement, Producer}

import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.matching.{Regex, UnanchoredRegex}
import scala.util.{Failure, Random, Success, Try}


package object utils {

  val logger = Logger(getClass)

  /**
    * A "cached" thread pool with unlimited size for database I/O which ReactiveMongo says is not blocking, but
    * which appears to block.  Note that cached thread pools can be dangerous due to the fact that they have
    * unlimited size queues.
    *
    * "For example even Scala’s ExecutionContext.Implicits.global has an upper limit to the number of threads
    * spawned, which means that you can end up in a dead-lock, because all of your threads can end up blocked,
    * with no threads available in the pool to finish the required callbacks.
    *   [https://monix.io/docs/3x/best-practices/blocking.html]
    *
    * See also:
    *   http://blog.jessitron.com/2014/01/choosing-executorservice.html
    *   https://stackoverflow.com/questions/17957382/fixedthreadpool-vs-cachedthreadpool-the-lesser-of-two-evils
    */
  object ExecutionContext {
    object CachedThreadPool {
      implicit lazy val global: ExecutionContext = Scheduler.io(name = "hamstoo-io")
    }
  }

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
    * @param uri        The database server's URI (which should include a database name).
    * @param nAttempts  Number of failed attempts before giving up and throwing an exception.
    */
  @tailrec
  final def getDbConnection(uri: String, nAttempts: Int = 5): (MongoConnection, String) = {
    MongoConnection.parseURI(uri).map { parsedUri =>
      if (dbDriver.isEmpty)
        initDbDriver()
      // the below doesn't work bc/ the second parameter is used as the Akka actor name, which must be unique when testing
      //dbDriver.get.connection(parsedUri, parsedUri.db, strictUri = false).get
      Logger.warn(s"Database name: ${parsedUri.db.get}")
      (dbDriver.get.connection(parsedUri), parsedUri.db.get)
    } match {
      case Success(conn) =>
        Logger.warn(s"Established connection to MongoDB via URI: ${maskDbUri(uri)}")
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

  /** Mask the username/password out of the database URI so that it can be logged. */
  def maskDbUri(uri: String): String = {
    val rgx = """^(.+//)\S+:\S+(@.+)$""".r // won't match docker-compose URI, but will match production/staging
    rgx.findFirstMatchIn(uri).map { m =>
      s"${m.group(1)}username:password${m.group(2)}"
    }.getOrElse(uri)
  }

  /** Used by backend: AuthController and MarksController. */
  def endpoint2Link(endpoint: Call, forceSecure: Boolean = false)(implicit request: Request[_]): String =
    httpHost(forceSecure) + endpoint
  def httpHost(forceSecure: Boolean)(implicit request: Request[_]): String =
    s"http${if (request.secure || forceSecure) "s" else ""}://${request.host}"
  def httpHost(implicit request: Request[_]): String = httpHost(forceSecure = false)

  /** Extended ReactiveMongo QueryBuilder */
  implicit class ExtendedQB(private val qb: GenericQueryBuilder[BSONSerializationPack.type]) extends AnyVal {

    /**
      * Short for `.cursor` with `.collect` consecutive calls with default error handler. Or maybe this is
      * short for "collection" analogous to `GenericQueryBuilder.one`.  Either way, it works.
      */
    def coll[E, C[_] <: Iterable[_]](n: Int = -1)
                                    (implicit r: Reader[E],
                                     cbf: CanBuildFrom[C[_], E, C[E]],
                                     ec: ExecutionContext): Future[C[E]] = {

      // "In most cases, modifying the batch size will not affect the user or the application, as the mongo shell and
      // most drivers return results as if MongoDB returned a single batch."
      //   [https://docs.mongodb.com/manual/reference/method/cursor.batchSize/]
      qb/*.options(QueryOpts().batchSize(n))*/.cursor[E]().collect[C](n, Cursor.FailOnError[C[E]]())
    }

    /** With embedded pagination */
/*    def pagColl[E, C[_] <: Iterable[_]](offset: Int = 0, limit: Int = -1)
                                        (implicit r: Reader[E], cbf: CanBuildFrom[C[_], E, C[E]]): Future[C[E]] = {
      qb.options(QueryOpts(skipN = offset)).cursor[E]().collect[C](limit, Cursor.FailOnError[C[E]]())
    }*/
  }

  /** Extend ReactiveMongo Index with a `%` method for naming indexes. */
  implicit class ExtendedIndex(private val i: Index) extends AnyVal {
    def %(name: String): (String, Index) = name -> i.copy(name = Some(name))
  }

  /** Extend ReactiveMongo CollectionIndexesManager with an `ensure` indexes method. */
  implicit class ExtendedIM(private val im: CollectionIndexesManager) extends AnyVal {
    def ensure(indxs: Map[String, Index])(implicit ec: ExecutionContext): Unit = for (is <- im.list) {
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

  /** Convenience function; useful to have for when skipping database queries for some reason or other. */
  def fNone[T]: Future[Option[T]] = Future.successful(Option.empty[T])
  def ftrue: Future[Boolean] = Future.successful(true)
  def ffalse: Future[Boolean] = Future.successful(false)

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

  implicit class ExtendedOption[T](private val mb: Option[T]) extends AnyVal {
    def toJson(fieldName: String)(implicit w: Writes[T]): JsObject = mb.fold(Json.obj())(x => Json.obj(fieldName -> x))
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
    def isReallyNaN: Boolean = d.isNaN || d.isInfinite
    def isFinitey: Boolean = !d.isReallyNaN // think `isTruthy`
    def coalesce(ifNaN: => Double): Double = if (d.isReallyNaN) ifNaN else d
    def coalesce0(implicit ev: Numeric[Double]): Double = coalesce(ev.zero) // using Numeric typeclass
    def or0: Double = coalesce0
    def ~=(bprecision: (Double, Double)): Boolean = { val (b, p) = bprecision; (d - b).abs < p }
    def ~=(b: Double): Boolean = this.~=((b, 1e-8))
    def !~=(bprecision: (Double, Double)): Boolean = !this.~=(bprecision)
    def !~=(b: Double): Boolean = !this.~=(b)
  }

  /**
    * Extended scala.util.Random
    */
  implicit class ExtendedRandom(private val rand: Random) extends AnyVal {

    /**
      * Given a sequence of bins with associated probabilities, return a pseudorandom bin index per the
      * given probability distribution.
      */
    def nextBin(bins: IndexedSeq[Double]): Option[Int] = if (bins.isEmpty) None else {
      val cumulativeSums = bins.scanLeft(0.0)(_ + _).tail // remove leading 0.0
      val x = rand.nextDouble * cumulativeSums.last
      cumulativeSums.zipWithIndex.find(x <= _._1).map(_._2)
    }
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
    /** Mega-seconds. */
    def Ms: Double = ms.toDouble / 1000000
    /** Time format. */
    def tfmt: String = s"${ms.dt} [${ms.Gs}]".replaceAll("T00:00:00.000", "").replaceAll(":00:00.000", "")
    /** Seconds format. */
    def sfmt: String = s"${ms.dt.getSecondOfDay} [${ms.Ms}]"
    /** Converts from time in milliseconds to a JsValueWrapper. */
    def toJson: Json.JsValueWrapper = {
      val date = this.dt
      s"${date.year.getAsString}-${date.monthOfYear.getAsString}-${date.dayOfMonth.getAsString}"
    }
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
  val d: BSONDocument = BSONDocument.empty
  val curnt: Producer[BSONElement] = com.hamstoo.models.Mark.TIMETHRU -> INF_TIME

  /** A couple regexes used in `parse` but that which may also be useful elsewhere. */
  val rgxRepeatedSpace: Regex = raw"\s{2,}".r.unanchored
  val rgxCRLFT: Regex = raw"[\n\r\t]".r.unanchored
  val rgxAlpha: UnanchoredRegex = "[^a-zA-Z]".r.unanchored // TODO: https://github.com/Hamstoo/hamstoo/issues/68

  /**
    * Mild string parsing.  Nothing too severe here as these parsed strings are what are stored in the database
    * as representations.  In particular, these strings should include punctuation for a few reasons: (1) we may
    * want to display excerpts of them on the My Marks page in human-readable form, (2) we may want to use them
    * as input to our own fit of word2vec or GloVe, and (3) the Conceptnet Numberbatch model is fitted with them
    * included.  This last point is part of what leads to a 418,000-word English vocabulary in which the following
    * words are all found independently: "can't", "can't", and "can't".
    */
  def parse(s: String): String = rgxRepeatedSpace.replaceAllIn(rgxCRLFT.replaceAllIn(s, " "), " ").trim

  /**
    * `parse` should've already been applied, but use \s+ anyway, just to be safe.  Lowercase'izing to make
    * the caching more efficient (TODO: https://github.com/Hamstoo/hamstoo/issues/68).
    */
  def tokenize(text: String): Seq[String] = text.toLowerCase(Locale.ENGLISH).split(raw"\s+")

  /** Moved this here to avoid cut-and-pasted code. */
  def tokenizeTags(tags: String): Set[String] = tags.split(',').map(_.trim).toSet - ""

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

  /** I can never remember how to assemble all the Writers to write a simple file in Java, so here it is. */
  def cleanlyWriteFile[B](filename: String)(doWork: java.io.BufferedWriter => B): Try[B] = {
    import java.io.{BufferedWriter => BW, FileOutputStream => FOS, OutputStreamWriter => OSW}
    utils.cleanly[BW, B](new BW(new OSW(new FOS(filename))))(_.close)(doWork)
  }

  /** Returns a string of memory statistics. */
  def memoryString: String =
    f"total: ${Runtime.getRuntime.totalMemory/1e6}%.0f, free: ${Runtime.getRuntime.freeMemory/1e6}%.0f"

  /**
    * Compute principal directions/axes as we don't really care about the actual principal components,
    * which are just a reduced dimensional approximation of the data.
    *
    * There are a couple issues with SVD-based clustering:
    * 1. Principal axes are adirectional, so we either must attempt to assign directions to them or use
    *    max(cos, -cos) when computing cosine similarities to them.  This is happening below.
    * 2. They're based on axes of maximum variance, so they may require words with vectors in opposite directions
    *    from each other to really be chosen as a top axis.  We're really only interested in positive similarity
    *    words though.
    *
    * @param weightedVecs      Input vectors, originally L2-normalized, but then weighted according to their importance.
    * @param nAxes             Desired number of principal axes to return.
    * @param mbOrientationVec  A vector, which if specified, will be used to orient the principal axes according to
    *                          whichever orientation direction has positive correlation to this vector.
    * @param bOrientAxes       If mbOrientationVec is None, another more simplistic/dumb orientation method will be
    *                          attempted.
    */
  def principalAxes(weightedVecs: Seq[Vec],
                    nAxes: Int,
                    mbOrientationVec: Option[Vec] = None,
                    bOrientAxes: Boolean = true): Seq[Vec] = weightedVecs.size match { // #words

    case n if n == 0 => Seq.empty[Vec]
    case n if n == 1 => Seq(weightedVecs.head)
    case n =>
      import com.hamstoo.models.Representation.VecFunctions
      val colMeans = weightedVecs.reduce(_ + _) / n
      logger.debug(s"principalAxes: colMeans.take(5) = ${colMeans.take(5)}")

      // Breeze vectors are column vectors, which is why the transpose is required below (to convert them to rows)
      val data: DenseMatrix[Double] = new DenseMatrix(n, weightedVecs.head.size) // e.g. n x 300
      weightedVecs.zipWithIndex.foreach { case (v, i) => data(i, ::) := DenseVector((v - colMeans).toArray).t }

      // X = USV' s.t. U = n x n (probably big!), S = n x 300 (diagonal), V = 300 x 300 (small'ish)
      val svd_ = svd(data)

      // adirectional principal directions/axes
      val aaxes = (0 until math.min(n, nAxes)).map(svd_.Vt(_, ::).t.toArray.toSeq)

      // The axes are 'adirectional' (i.e. they can point in either direction along their line) but we're interested
      // in vectors that are *positively* correlated with words that are maximally representative of the text, so we
      // need to choose a sign for each vector.  If one word had a huge tf*idf that overcame all other words, then
      // we'd expect the correlation of that word's word vector to the first principal axis to be close to either 1
      // or -1, so one way to select the direction of the vector could be based on this metric: sign(max(corrs) +
      // min(corrs)).  Given that we typically don't have such huge tf*idfs another way to do this might be to use
      // sign(skew(corrs)).
      // UPDATE - Once the EXPONENT gets set down to around 1.0, skew isn't biased enough anymore, so just use
      // sign(max-min) as originally thought.  This will effectively align the vector with the highest n*idf word.
      // UPDATE2 - Rather than using skew or highest (which can be unstable), just align PC vectors with IDF vector.
      val axes = if (!bOrientAxes && mbOrientationVec.isEmpty) aaxes else {

        aaxes.map { ax =>
          val sign = mbOrientationVec.map(_ cosine ax).getOrElse {
            val corrs = weightedVecs.map(_ cosine ax) // using `cosine` here b/c it's faster than `corr`
            /*val skew = corrs.skew*/
            /*if (math.abs(skew) < 1e-5)*/ corrs.max + corrs.min /*else corrs.skew*/
          }
          // sign == 0.0 e.g. happens if n==2
          (ax * (if (sign == 0.0) 1.0 else sign)).l2Normalize
        }
      }

      // debugging
      /*if (true) {
        axes.zipWithIndex.foreach { case (ax, i) =>
          val corrs = topWords.map { case (w, v) => w -> (v corr ax) }.toSeq.sortBy(-_._2)
          println(f"ax$i: sum=${corrs.map(_._2).stdev}%.4f skew=${corrs.map(_._2).skew}%.4f $corrs")
        }
      }*/

      axes
  }

  /** Print a vector similarity matrix to stdout. */
  def printSimilarityMatrix(vs: Seq[(String, Vec)],
                            similarityFns: Seq[String] = Seq("correlation"/*, "cosine similarity"*/),
                            printer: (String) => Unit = print): Unit = {
    import com.hamstoo.models.Representation.VecFunctions
    if (vs.nonEmpty) {
      similarityFns.foreach { which =>
        printer(s"\nDocument vector $which matrix (n = ${vs.size}):\n      ")
        vs.foreach { col => printer(f"  ${col._1}%5s") }
        printer("\n")
        vs.foreach { row =>
          printer(f"${row._1}%6s")
          vs.foreach { col =>
            val x = if (which == "correlation") row._2 corr col._2 else row._2 cosine col._2
            val color = if (x == 1.0) 90 // dark gray (https://misc.flogisoft.com/bash/tip_colors_and_formatting)
            else if (x > 0.995) 37 // light gray
            else if (x < -0.5) 31 // red
            else if (x < -0.05) 35 // magenta
            else if (x > 0.9) 32 // green
            else if (x > 0.6) 33 // yellow
            printer(f"\u001b[${color}m  $x%+.2f\u001b[0m")
          }
          printer("\n")
        }
        printer("\n")
      }
    }
  }
}
