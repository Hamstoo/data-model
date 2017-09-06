package com.hamstoo

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

  private val URL_PREFIX_LENGTH = 1000

  implicit class ExtendedString(private val s: String) extends AnyVal {
    /**
      * Retrieves first chars of a string as binary sequence. This method exists as a means of constructing
      * binary prefixes of string fields for binary indexes in MongoDB.
      */
    def prefx: mutable.WrappedArray[Byte] = s.getBytes take URL_PREFIX_LENGTH
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

}
