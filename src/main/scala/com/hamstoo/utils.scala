package com.hamstoo

import reactivemongo.api.BSONSerializationPack.Reader
import reactivemongo.api.collections.GenericQueryBuilder
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.{CollectionIndexesManager, Index}
import reactivemongo.api.{BSONSerializationPack, Cursor}

import scala.collection.generic.CanBuildFrom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.higherKinds
import scala.reflect.runtime.universe
import scala.reflect.runtime.universe.TypeTag

package object utils {
  /**
    * This function uses the experimental Scala reflection API to obtain the name of a
    * type's field.  Ideally the field wouldn't have to be passed in as a `String` but
    * could rather be passed in as a first-class object, e.g. `Class::field`, which appears
    * to be possible in Java 8 (http://openjdk.java.net/jeps/118)--as well as other
    * languages.  What it does do successfully however is that it allows dependencies,
    * particularly those required for JSON (de)serialization, to be type-checked at runtime.
    *
    * @see https://stackoverflow.com/questions/34060376/get-method-function-variable-name-as-string-in-scala
    * @see http://docs.scala-lang.org/overviews/reflection/overview.html
    */
  def fieldName[T](expectedFieldName: String)(implicit tag: TypeTag[T]): String = {
    val symbol = universe.typeOf[T] decl (universe TermName expectedFieldName)
    val field = try {
      symbol.asTerm
    }
    catch {
      case e: ScalaReflectionException =>
        throw new RuntimeException(s"`$expectedFieldName` is not a field of `${tag.tpe.toString}`", e)
    }
    field.name.decodedName.toString
  }

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
      if (wr.ok) Future.successful() else Future failed new Exception(wr.writeErrors mkString "; ")
  }

  private val URL_PREFIX_LENGTH = 1000

  implicit class StrWithBinaryPrefix(private val s: String) extends AnyVal {
    /**
      * Retrieves first chars of a string as binary sequence. This method exists as a means of constructing
      * binary prefixes of string fields for binary indexes in MongoDB.
      */
    def prefx: Array[Byte] = s.getBytes take URL_PREFIX_LENGTH
  }

  /* Rather than overriding `equals` and `hashCode` for every case class that has a Java.Array member, it
   * might be better to have a HashableArray class that would just do that for us, but I can't get it working.
    */
  /**
    * This implicit class cannot be used implicitly because it overrides methods that are already
    * defined for Array[T], but it can be used explicitly to override said methods.  In particular
    * Array[T] inherits its hashCode method from Object/Any, so Array[T] instances are insufficient
    * as members of case classes.
    * See also:
    *   https://stackoverflow.com/questions/20699105/using-implicit-class-to-override-method
    * /
  implicit class HashableArray[T](val ary: Array[T]) {

    /** Fairly standard equals definition. */
    override def equals(other: Any): Boolean = other match {
      case other: Array[T] => other.canEqual(this) && this.hashCode == new HashableArray(other).hashCode
      case _ => false
    }

    /**
      * Same implementation as Java.List per here:
      *   https://stackoverflow.com/questions/15576009/how-to-make-hashmap-work-with-arrays-as-key
      */
    override def hashCode(): Int = ary.foldLeft(1) { case (acc, elem) => 31 * acc + elem.hashCode }
  }

  /** Does this require scala.collection.generic.GenericCompanion? */
  object HashableArray {
    implicit val hashableByteArrayHandler: BSONDocumentHandler[HashableArray[T]] = Macros.handler[HashableArray[T]]
    def apply[T](ary: Array[T]) = new HashableArray(ary)
    def unapply[T](h: HashableArray[T]): Array[T] = h.ary
  }*/
}
