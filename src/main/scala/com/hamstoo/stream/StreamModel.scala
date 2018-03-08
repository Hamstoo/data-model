package com.hamstoo.stream

import com.google.inject.Injector
import play.api.Logger

import scala.collection.mutable
import scala.reflect._
import scala.reflect.runtime.universe._ // TypeTag & typeTag

/**
  *
  * @param injector
  */
class StreamModel(injector: Injector) {

  val logger: Logger = Logger(classOf[StreamModel])


  val facets = mutable.Map.empty[String, DataStream[Double]]


  def add[T <:DataStream[Double] :TypeTag :ClassTag](/*name: String*/): Unit = {

    // def add[T : TypeTag](): Unit = println(typeTag[T].tpe.erasure.toString)
    //val name: String = classOf[T].getSimpleName
    //val name: String = classTag[T].erasure.toString
    val name: String = typeTag[T].tpe.erasure.toString
    logger.info(s"Adding data stream: $name")

    // note that scalaguice still uses old Scala version implicit Manifests
    import net.codingwell.scalaguice.InjectorExtensions._
    val t: T = injector.instance[T]

    val ds = t.asInstanceOf[DataStream[Double]]

    facets += name -> ds
  }

}
