package com.hamstoo.stream

import akka.NotUsed
import akka.stream.{Materializer, SourceShape}
import akka.stream.scaladsl.{GraphDSL, Merge, Sink, Source}
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

  // a set of all the facets/statistics/metrics to be computed by this model
  protected val facets = mutable.Map.empty[String, DataStream[Double]]

  /** Add a facet to be computed by this model. */
  def add[T <:DataStream[Double] :TypeTag :ClassTag](mbName: Option[String] = None): Unit = {

    val name: String = mbName.getOrElse(classTag[T].runtimeClass.getSimpleName)
    logger.info(s"Adding data stream: $name")
    if (facets.contains(name))
      throw new Exception(s"Duplicate '$name' named facets detected")

    // note that scalaguice still uses old Scala version implicit Manifests (presumably for backwards compatibility)
    import net.codingwell.scalaguice.InjectorExtensions._
    val t: T = injector.instance[T]

    val ds = t.asInstanceOf[DataStream[Double]]

    facets += name -> ds
  }

  /**
    * Run the entire model, including starting the clock.
    * @param sink  The sink to use to run the source.
    * @tparam T  Depends on the type of sink passed in.  `Sink.seq` would mean T is a `Seq[Data[Double]]` while
    *            `Sink.fold[Double, Data[Double]]` would mean T is a lonely `Double`.
    * @return  Returns a future containing the result of the sink.
    */
  def run[T](sink: Sink[Data[Double], Future[T]]): Future[T] = {

    // simultaneously allow the facets to start producing data (it's safer to start the clock after materialization
    // because of weird buffer effects and such)
    val result = source.runWith(sink)
    clock.start()

    result
  }

  /** Wire up the model by merging all the individual facets, but don't run it, and don't start the clock. */
  def source: Source[Data[Double], NotUsed] = Source.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[Data[Double]](facets.size))

    // wire all of the facets into the hub
    facets.zipWithIndex.foreach { case ((name, ds), i) =>

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds.source.map { data =>
        data.copy(values = data.values.map { case (eid, value) =>
          EntityId.merge(FacetName(name), eid) -> value
        })
      }.named(name) // unsure if this actually has any effect

      labeledSource ~> merge.in(i)
    }

    SourceShape(merge.out)
  })

  /**
    * No need for this to use a MergeHub (see Merge implementation above), which I couldn't get
    * working anyway because the MergeHub's `result` Future never seems to complete.
    */
  /*def run[T](sink: Sink[Data[Double], Future[T]]): Future[T] = {

    // materialize the MergeHub so that we can dynamically wire facets into it
    // see here https://github.com/akka/akka/issues/21693 for how I figured out the `toMat(sink)(Keep.both)` part
    val ppbs = DataStream.DEFAULT_BUFFER_SIZE
    val (hubSink, result): (Sink[Data[Double], NotUsed], Future[T]) =
      MergeHub.source[Data[Double]](perProducerBufferSize = ppbs).toMat(sink)(Keep.both).run()

    // wire all of the facets into the hub
    for((name, ds) <- facets) {

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds.source.map { data =>
        data.copy(values = data.values.map { case (eid, value) =>
          EntityId.merge(FacetName(name), eid) -> value
        })
      }.named(name) // unsure if this actually has any effect

      labeledSource.runWith(hubSink)
    }

    clock.start()
    result
  }*/
}
