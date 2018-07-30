/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.config

import akka.NotUsed
import akka.event.Logging
import akka.stream.scaladsl.{GraphDSL, Merge, Sink, Source}
import akka.stream.{Attributes, Materializer, SourceShape}
import com.google.inject.{Inject, Injector, Singleton}
import com.hamstoo.stream.facet._
import com.hamstoo.stream.{Clock, DataStream, injectorly}
import com.hamstoo.stream.Data.{Data, ExtendedData}
import com.hamstoo.stream.OptionalInjectId
import com.hamstoo.utils.{ExtendedDouble, ExtendedTimeStamp}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.runtime.universe.TypeTag
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

/**
  * A "facets model" is a collection of facet-computing streams.  Running the model generates a merged stream of
  * values containing all of the facets with their own labels.
  */
class FacetsModel @Inject()(clock: Clock)
                           (implicit injector: Injector, mat: Materializer) {

  import FacetsModel._
  val logger: Logger = Logger(classOf[FacetsModel])
  logger.debug(s"Constructing model: ${classOf[FacetsModel].getName}")

  // a set of all the facets/statistics/metrics to be computed by this model
  protected val facets = mutable.Map.empty[String, DataStream[_]]

  /** Add a facet to be computed by this model. */
  def add[T <:DataStream[_] :ClassTag :TypeTag](mbName: Option[String] = None): Unit = {

    val cls: Class[_] = classTag[T].runtimeClass
    val name: String = mbName.getOrElse(cls.getSimpleName)
    logger.debug(s"Adding data stream: $name")
    if (facets.contains(name))
      throw new Exception(s"Duplicate '$name' named facets detected")

    // as of 2018-7-30, all coefficients are now applied outside FacetsModel (in the frontend actually) where they can
    // be modified without having to re-query the backend with new search parameters
    facets += name -> injectorly[T]
/*
    // first lookup a *default* arg, or just 1.0 if there isn't one available
    val default = getDefaultArg[T]

    // pluck the (non-default, possibly overridden) arg from the injector
    val argGetter = new OptionalInjectId(name.toLowerCase, default)
    argGetter.injector_(Some(injector))
    val arg: Double = argGetter.value

    // coefficients are applied outside of the facet implementations themselves so that they and lower/upper
    // bounds can be applied independently (o/w applying bounds could involve backing out the coefficients)
    val ds: T = injectorly[T]
    val beta = ds.coefficient(arg)
    logger.info(f"\u001b[33mFACET\u001b[0m: $name($arg%.2f) = $beta%.2f")

    import com.hamstoo.stream.StreamDSL._
    facets += name -> (beta match {
      case b if b ~= 0.0 => ds.map(x => if (x.isReallyNaN) Double.NaN else 0.0)
      case b if b ~= 1.0 => ds
      case b             => ds * b // will only work with DataStream[Double], which is why this function requires it
    })
*/
  }

  /**
    * Run the entire model, including starting the clock.
    * @param sink  The sink to use to run the source.
    * @tparam T  Depends on the type of sink passed in.  `Sink.seq` would mean T is a `Seq[Data[Double]]` while
    *            `Sink.fold[Double, Data[Double]]` would mean T is a lonely `Double`.
    * @return  Returns a future containing the result of the sink.
    */
  def run[T](sink: Sink[OutType, Future[T]]): Future[T] = {

    // these actually do work, you just have to manually insert `.log("msg")` steps into the stream graph
    // (note `withAttributes` is commented out below)
    val attrs = Attributes.logLevels(onElement = Logging.DebugLevel,
                                     onFinish  = Logging.DebugLevel,
                                     onFailure = Logging.DebugLevel)

    // simultaneously allow the facets to start producing data (it's safer to start the clock after materialization
    // because of weird buffer effects and such)
    val result = out/*.withAttributes(attrs)*/.runWith(sink)
    clock.start()

    result
  }

  /** Flatten Data batches/snapshots into individual Datum and stream out those. */
  def flatRun[T](sink: Sink[OutType, Future[T]]): Future[T] = {
    val result = out.mapConcat { case (name, d) => d.asInstanceOf[Data[_]].map((name, _)) }.runWith(sink)
    clock.start()
    result
  }

  /** Wire up the model by merging all the individual facets, but don't run it, and don't start the clock. */
  def out: Source[OutType, NotUsed] = Source.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._

    val merge = b.add(Merge[OutType](facets.size))

    // wire all of the facets into the hub
    facets.zipWithIndex.foreach { case ((name, ds), i) =>

      // label the source with its facet name so that we can tell them apart on the other side
      val labeledSource = ds()
        .map { d => logger.debug(s"(\u001b[2m${name}\u001b[0m) ${d.sourceTimeMax.tfmt} (n=${d.size})"); d }
        .map { d => (name, d) }
        .named(name)

      labeledSource ~> merge.in(i)
    }

    SourceShape(merge.out)
  })
}

object FacetsModel {

  type OutType = (String, AnyRef)

  /**
    * Factory function to construct a default FacetsModel.
    * TODO: All of this (the set of facets and their args) should be configured in some sort of resource file.
    */
  @Singleton
  case class Default @Inject()(clock: Clock)
                              (implicit injector: Injector, mat: Materializer)
      extends FacetsModel(clock)(injector, mat) {

    // An injected instance of a stream can only be reused (singleton) if its defined inside a type (e.g. see Recency).
    // But eventually (perhaps) we can automatically generate new types (e.g. add[classOf[Recency] + 2]) or lookup
    // nodes in the injected Akka stream graph by name.
    //   https://www.google.com/search?q=dynamically+create+type+scala&oq=dynamically+create+type+scala&aqs=chrome..69i57.5239j1j4&sourceid=chrome&ie=UTF-8

    // switched back to using `add` on 2018-7-30
    //facets += classOf[SearchResults].getSimpleName -> injectorly[SearchResults]
    add[SearchResults]() // no longer works now that FacetsModel.add's T isn't a DataStream[_]

    // as of 2018-7-30, AggregateSearchScore is no longer a bonafide facet given that its values can be gotten from the
    // SearchResults data stream, in addition all coefficients are now applied outside FacetsModel (in the frontend
    // actually) where they can be modified without having to re-query the backend with new search parameters
    //add[AggregateSearchScore]()

    add[Recency]() // see How to Think screenshot
    //add(ConfirmationBias)
    add[ImplicitRating]()
    add[Rating]()
    add[UserSimilarity]()
  }

  /**
    * Get DEFAULT_ARG automagically from companion objects.
    *   https://stackoverflow.com/questions/36290863/get-field-value-of-a-companion-object-from-typetagt
    * TODO: load defaults from a resource file
    */
  def getDefaultArg[T :ClassTag]: Double = Try {
    val cls: Class[_] = classTag[T].runtimeClass
    import scala.reflect.runtime.{currentMirror, universe}
    val companionSymbol = currentMirror.classSymbol(cls).companion.asModule
    val companionInstance = currentMirror.reflectModule(companionSymbol.asModule)
    val companionMirror   = currentMirror.reflect(companionInstance.instance)
    val fieldSymbol = companionSymbol.typeSignature.decl(universe.TermName("DEFAULT_ARG")).asTerm
    val fieldMirror = companionMirror.reflectField(fieldSymbol)
    fieldMirror.get.asInstanceOf[Double]
  }.getOrElse(1.0)

  /**
    * TODO: perhaps instead of the reflection above we should require each companion object to extend this trait?
    * TODO:   though reflection would still be required to get from the class to the companion, wouldn't it?
    */
//  trait FacetArg {
//
//    def default: Double = 1.0
//
//    /** A (overrideable) default implementation to convert a facet argument into a FacetsModel coefficient. */
//    def coefficient(arg: Double): Double = arg
//
//  }
}
