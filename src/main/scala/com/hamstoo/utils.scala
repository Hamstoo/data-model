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
      val exIs = is.flatMap[String, Set[String]](_.name)
      exIs -- indxs.keySet - "_id_" foreach im.drop
      indxs.keySet -- exIs foreach { n => im.ensure(indxs(n)) }
    }
  }

  private val URL_PREFIX_LENGTH = 1000

  implicit class StrWithBinaryPrefix(private val s: String) extends AnyVal {
    /**
      * Retrieves first chars of a string as binary sequence. This method exists as a means of constructing
      * binary prefixes of string fields for binary indexes in MongoDB.
      */
    def prefx(): Array[Byte] = s.getBytes take URL_PREFIX_LENGTH
  }

  /** Checks reactivemongo's update functions results for errors and forms a unified return. */
  def digestWriteResult[T]: (WriteResult, T) => Either[String, T] = (r, o) =>
    if (r.ok) Right(o) else Left(r.writeErrors mkString "; ")
}
