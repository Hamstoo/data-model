package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, MergeHub, Sink}
import com.google.inject.{Inject, Injector}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect._ // ClassTag
import scala.reflect.runtime.universe._ // TypeTag & typeTag

/**
  * A "facets model" is a collection of facet-computing streams.  Running the model generates a merged stream of
  * values containing all of the facets with their own labels.
  */
class FacetsModel @Inject()(injector: Injector)
                           (implicit clock: Clock, materializer: Materializer) {

  val logger: Logger = Logger(classOf[FacetsModel])

  // set of all facets to be computed by this model
  protected val facets = mutable.Map.empty[String, DataStream[Double]]

  /** Add a facet to be computed by this model. */
  def add[T <:DataStream[Double] :TypeTag :ClassTag](/*name: String*/): Unit = {

    val name: String = typeTag[T].tpe.erasure.toString
    logger.info(s"Adding data stream: $name")

    // note that scalaguice still uses old Scala version implicit Manifests (presumably for backwards compatibility)
    import net.codingwell.scalaguice.InjectorExtensions._
    val t: T = injector.instance[T]

    val ds = t.asInstanceOf[DataStream[Double]]

    facets += name -> ds
  }

  /** Run the entire model, including starting the clock. */
  def run(sink: Sink[Data[Double], Future[Seq[Data[Double]]]]): Future[Seq[Data[Double]]] = {

    // this properly gets the results into a collection when the sink is a Sink.seq
    val result = facets.head._2.source.runWith(sink)

    // materialize the MergeHub so that we can dynamically wire facets into it
    // see here https://github.com/akka/akka/issues/21693 for how I figured out the `toMat(sink)(Keep.both)` part
    /*val ppbs = DataStream.DEFAULT_BUFFER_SIZE
    val (hubSink, result): (Sink[Data[Double], NotUsed], Future[Seq[Data[Double]]]) =
      MergeHub.source[Data[Double]](perProducerBufferSize = ppbs).toMat(sink)(Keep.both).run()

    // wire all of the facets into the hub
    for((name, ds) <- facets) {

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds.source.map { data =>
        data.copy(values = data.values.map { case (eid, value) =>
          EntityId.merge(FacetName(name), eid) -> value
        })
      }

      labeledSource.named(name).runWith(hubSink)
    }*/

    // simultaneously allow the facets to start producing data
    clock.start()

    result
  }
}
