/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.config

import akka.NotUsed
import akka.stream.scaladsl.{GraphDSL, Merge, Sink, Source}
import akka.stream.{Materializer, SourceShape}
import com.google.inject.{Inject, Injector, Singleton}
import com.hamstoo.stream.facet.{Recency, SearchResults}
import com.hamstoo.stream.{Clock, DataStreamBase}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.{ClassTag, classTag}

/**
  * A "facets model" is a collection of facet-computing streams.  Running the model generates a merged stream of
  * values containing all of the facets with their own labels.
  */
class FacetsModel @Inject() (injector: Injector)
                            (implicit clock: Clock, materializer: Materializer) {

  import FacetsModel._
  val logger: Logger = Logger(classOf[FacetsModel])
  logger.info(s"Constructing model: ${classOf[FacetsModel].getName}")

  // a set of all the facets/statistics/metrics to be computed by this model
  protected val facets = mutable.Map.empty[String, DataStreamBase]

  /** Add a facet to be computed by this model. */
  def add[T <:DataStreamBase :ClassTag :TypeTag](mbName: Option[String] = None): Unit = {

    val name: String = mbName.getOrElse(classTag[T].runtimeClass.getSimpleName)
    logger.info(s"Adding data stream: $name")
    if (facets.contains(name))
      throw new Exception(s"Duplicate '$name' named facets detected")

    // note that scalaguice still uses old Scala version implicit Manifests (presumably for backwards compatibility)
    import net.codingwell.scalaguice.InjectorExtensions._
    val t: T = injector.instance[T]

    val ds = t.asInstanceOf[DataStreamBase]

    facets += name -> ds
  }

  /**
    * Run the entire model, including starting the clock.
    * @param sink  The sink to use to run the source.
    * @tparam T  Depends on the type of sink passed in.  `Sink.seq` would mean T is a `Seq[Data[Double]]` while
    *            `Sink.fold[Double, Data[Double]]` would mean T is a lonely `Double`.
    * @return  Returns a future containing the result of the sink.
    */
  def run[T](sink: Sink[OutType, Future[T]]): Future[T] = {

    // simultaneously allow the facets to start producing data (it's safer to start the clock after materialization
    // because of weird buffer effects and such)
    val result = source.runWith(sink)
    clock.start()

    result
  }

  /** Wire up the model by merging all the individual facets, but don't run it, and don't start the clock. */
  def source: Source[OutType, NotUsed] = Source.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[OutType](facets.size))

    // wire all of the facets into the hub
    facets.zipWithIndex.foreach { case ((name, ds), i) =>

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds.source.map { data =>
        (name, data)
      }.named(name) // unsure if this actually has any effect

      labeledSource ~> merge.in(i)
    }

    SourceShape(merge.out)
  })
}

object FacetsModel {

  type OutType = (String, AnyRef)

  /** Factory function to construct a default FacetsModel. */
  @Singleton
  case class Default @Inject() (injector: Injector, clock: Clock, materializer: Materializer)
      extends FacetsModel(injector)(clock, materializer) {

    // An injected instance of a stream can only be reused (singleton) if its defined inside a type (e.g. see Recency).
    // But eventually (perhaps) we can automatically generate new types (e.g. add[classOf[Recency] + 2]) or lookup
    // nodes in the injected Akka stream graph by name.
    //   https://www.google.com/search?q=dynamically+create+type+scala&oq=dynamically+create+type+scala&aqs=chrome..69i57.5239j1j4&sourceid=chrome&ie=UTF-8

    add[SearchResults]()
    add[Recency]() // see How to Think screenshot
    //add(ConfirmationBias)
    //add(TimeSpent)
    //add(Rating)
    //add(SearchRelevance)
  }
}
