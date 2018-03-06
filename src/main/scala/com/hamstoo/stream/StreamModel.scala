package com.hamstoo.stream

import com.google.inject.Injector

import scala.collection.mutable
import scala.reflect._ // ClassTag & classTag
import scala.reflect.runtime.universe._ // TypeTag & typeTag

/**
  *
  * @param injector
  */
class StreamModel(injector: Injector) {


  val facets = mutable.Map.empty[String, DataStream[Double]]


  // TODO: switch Manifest to TypeTag or ClassTag (https://docs.scala-lang.org/overviews/reflection/typetags-manifests.html)
  def add[T <:DataStream[Double] :TypeTag :ClassTag](/*name: String*/): Unit = {

    // def add[T : TypeTag](): Unit = println(typeTag[T].tpe.erasure.toString)
    //val name: String = classOf[T].getSimpleName
    //val name: String = classTag[T].erasure.toString
    val name: String = typeTag[T].tpe.erasure.toString

    import net.codingwell.scalaguice.InjectorExtensions._
    val t: T = injector.instance[T]

    val ds = t.asInstanceOf[DataStream[Double]]

    facets += name -> ds
  }

}
