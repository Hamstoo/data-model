package com.hamstoo.stream

import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, MergeHub, Sink}
import com.google.inject.{Inject, Injector}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect._ // ClassTag
import scala.reflect.runtime.universe._ // TypeTag & typeTag

/**
  * A "stream model" is a collection of facet-computing streams.  Running the model generates streams of
  * values for each of the facets over time.
  */
class StreamModel @Inject() (injector: Injector)
                            (implicit clock: Clock, materializer: Materializer) {

  val logger: Logger = Logger(classOf[StreamModel])

  // set of all facets to be computed by this model
  protected val facets = mutable.Map.empty[String, DataStream[Double]]

  /** Add a facet to be computed by this model. */
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

  /** Run the entire model, including starting the clock. */
  @Inject
  def run(sink: Sink[Data[Double], Future[Seq[Data[Double]]]]): Future[Seq[Data[Double]]] = {

    // materialize the MergeHub so that we can dynamically wire facets into it
    // see here https://github.com/akka/akka/issues/21693 for how I figured out the `toMat(sink)(Keep.both)` part
    val ppbs = DataStream.DEFAULT_BUFFER_SIZE
    val (hubSink, result) = MergeHub.source[Data[Double]](perProducerBufferSize = ppbs).toMat(sink)(Keep.both).run()

    // wire all of the facets into the hub
    for((name, ds) <- facets) {

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds.source.map { data =>
        data.copy(values = data.values.map { case (eid, value) =>
          EntityId.merge(FacetName(name), eid) -> value
        })
      }

      labeledSource.toMat(hubSink)(Keep.right).run()
    }

    // allow the facets to start producing data
    clock.start()

    result
  }
}
