/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.name.{Named, Names}
import com.google.inject._
import com.google.inject.multibindings.OptionalBinder
import com.hamstoo.services.VectorEmbeddingsService
import com.hamstoo.services.VectorEmbeddingsService.Query2VecsType
import net.codingwell.scalaguice.{ScalaModule, ScalaOptionBinder, typeLiteral}
import play.api.Logger

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  *
  * This StreamModule trait requires an implementation of `configure`.
  */
abstract class StreamModule extends AbstractModule with ScalaModule {

  val logger = Logger(classOf[StreamModule])

  // having an `implicit this` enables the `:=` methods of InjectId and this class, StreamModule
  implicit val implicitThis: StreamModule = this

  /** Configure optional default values. */
  override def configure(): Unit = {
    super.configure()
    logger.info(s"Configuring module: ${classOf[StreamModule].getName}")

    Query2VecsOptional ?= None
    SearchUserIdOptional ?= None // Option.empty[UUID]

    //assignOptional3(SearchUserIdOptional, None)

    //val key = Key.get(classOf[Option[UUID]], Names.named("search.user.id")) // doesn't work with all
    //val key = Key.get(typeLiteral[Option[UUID]], Names.named("search.user.id")) // works with all

    //assignOptional(SearchUserIdOptional, None) // doesn't work
    //assignOptional[Option[UUID]](SearchUserIdOptional, Option.empty[UUID]) // doesn't work
    //assignOptional2[Option[UUID]](SearchUserIdOptional, Option.empty[UUID], typeLiteral[Option[UUID]]) // works

    //implicit val tl = new TypeLiteral[Option[UUID]]() {} // works
    //implicit val tl = SearchUserIdOptional.tl // doesn't work (works now w/ :Manifest)
    //implicit val tl = typeLiteral[Option[UUID]] // works
    //assignOptional2[Option[UUID]](SearchUserIdOptional, Option.empty[UUID]) // works w/ implicit fn arg, not w/ context bound

    //implicit val tl = typeLiteral[Option[UUID]]
    //assignOptional1[Option[UUID]](SearchUserIdOptional, Option.empty[UUID]) // works w/ implicit fn arg or context bound

    //ScalaOptionBinder.newOptionBinder[SearchUserIdOptional.typ](binder, Names.named(SearchUserIdOptional.name))
    //  .setDefault.toInstance(None) // works

    //ScalaOptionBinder.newOptionBinder(binder, Key.get(typeLiteral[SearchUserIdOptional.typ], Names.named(SearchUserIdOptional.name)))
      //.setDefault.toInstance(None) // works

    //ScalaOptionBinder.newOptionBinder[Option[UUID]](binder, Names.named("search.user.id")).setDefault.toInstance(None) // works
    //OptionalBinder.newOptionalBinder(binder(), key).setDefault().toInstance(None) // doesn't work w/ Key.get(classOf)
    //bind(key).toInstance(None) // doesn't work w/ Key.get(classOf)
    //bind[Option[UUID]].annotatedWith(Names.named("search.user.id")).toInstance(None) // works
    //bind(classOf[Option[UUID]]).annotatedWith(Names.named("search.user.id")).toInstance(None) // doesn't work

  }

  /** An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal? */
  implicit class InjectVal[T :ClassTag :TypeTag](private val _typ: Class[T]) /*extends AnyVal*/ {
    def :=(instance: T): Unit = new NamelessInjectId[T] := instance
    def ?=(instance: T): Unit = new NamelessInjectId[T] ?= instance
  }

  /** Bind a (possibly named) instance given its (type, name) pair, which Guice uses to uniquely identify bindings. */
  def assign[T :ClassTag :TypeTag](injectId: NamelessInjectId[T], instance: T): Unit = injectId match {
    case iid: InjectId[_] => bind[iid.typ].annotatedWith(Names.named(iid.name)).toInstance(instance)
    case iid /*Nameless*/ => bind[iid.typ]                                     .toInstance(instance)
  }

  /** Bind an optional. */
  def assignOptional[T :ClassTag :TypeTag](injectId: NamelessInjectId[T], instance: T): Unit = {
    injectId match {
      // "To bind a specific name, use Names.named() to create an instance to pass to annotatedWith"
      case iid: InjectId[_] =>
        ScalaOptionBinder.newOptionBinder[iid.typ](binder, Names.named(iid.name)).setDefault.toInstance(instance)
      //ScalaOptionBinder.newOptionBinder(binder, Key.get(tl, Names.named(iid.name))).setDefault.toInstance(instance)
      case iid /*Nameless*/ =>
        ScalaOptionBinder.newOptionBinder[iid.typ](binder                       ).setDefault.toInstance(instance)
        //ScalaOptionBinder.newOptionBinder(binder, Key.get(tl)                       ).setDefault.toInstance(instance)
    }
  }

  /** Don't use scala-guice.  Can't get this working any way. */
  def assignOptional1[T :ClassTag :TypeTag: TypeLiteral](injectId: NamelessInjectId[T], instance: T): Unit = {
    //val ct = implicitly[reflect.ClassTag[T]]
    //val clazz: Class[T] = ct.runtimeClass.asInstanceOf[Class[T]]

    val tl = implicitly[TypeLiteral[T]] // works

    val key = injectId match {
      // "To bind a specific name, use Names.named() to create an instance to pass to annotatedWith"
      case iid: InjectId[_] =>
        Key.get(tl, Names.named(iid.name))
      case iid /*Nameless*/ =>
        Key.get(tl)
    }
    //OptionalBinder.newOptionalBinder(binder(), key).setDefault().toInstance(instance) // works
    ScalaOptionBinder.newOptionBinder(binder, key).setDefault.toInstance(instance) // works
  }

  def assignOptional2[T :Manifest](injectId: NamelessInjectId[T], instance: T): Unit = {

    //val tl: TypeLiteral[injectId.typ] = scalaguice.typeLiteral[injectId.typ] // doesn't work
    //val tl = implicitly[TypeLiteral[T]] // works
    //val tl = implicitly[TypeLiteral[injectId.typ]] // works, but didn't work when using parens (not square brackets)
    val tl = typeLiteral[injectId.typ] // doesn't work (too much has been erased to use scalaguice's typeLiteral?)
    //val tl = new TypeLiteral[T] {} // doesn't work, but lots more errors

    injectId match {
      // "To bind a specific name, use Names.named() to create an instance to pass to annotatedWith"
      case iid: InjectId[_] =>
        ScalaOptionBinder.newOptionBinder(binder, Key.get(tl, Names.named(iid.name))).setDefault.toInstance(instance)
      case iid /*Nameless*/ =>
        ScalaOptionBinder.newOptionBinder(binder, Key.get(tl)                       ).setDefault.toInstance(instance)
    }
  }

  def assignOptional3[T :ClassTag :TypeTag](injectKey: Key[T], instance: T): Unit = {
    ScalaOptionBinder.newOptionBinder(binder, injectKey).setDefault.toInstance(instance)
  }

  /** See Query2VecsOptional.  There are 2 providers of objects named "query2Vecs" but they return different types. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2VecsOptional(@Named(Query.name) query: Query.typ, vecSvc: VectorEmbeddingsService)
                               (implicit ec: ExecutionContext): Query2VecsOptional.typ =
    Some(vecSvc.query2Vecs(query))

  /** One of the providers is needed for when "query2Vecs" is optional and the other, this one, for when it isn't. */
  @Provides @Singleton @Named(Query2VecsOptional.name)
  def provideQuery2Vecs(@Named(Query2VecsOptional.name) mbQuery2Vecs: Query2VecsOptional.typ): Query2VecsType =
    mbQuery2Vecs.get

  @Provides @Singleton
  def buildModel(injector: Injector, clock: Clock, materializer: Materializer): FacetsModel =
                                                              new FacetsModel(injector)(clock, materializer) {

    //import net.codingwell.scalaguice.InjectorExtensions._
    //val qc: QueryCorrelation = injector.instance[QueryCorrelation]

    add[SearchResults]() // "semanticRelevance"

    // * so a stream can only be reused (singleton) if its defined inside a type
    //   * but eventually we can make this work to automatically generate new types (perhaps)
    //   * add[classOf[QueryCorrelation] + 2]()
    //   * https://www.google.com/search?q=dynamically+create+type+scala&oq=dynamically+create+type+scala&aqs=chrome..69i57.5239j1j4&sourceid=chrome&ie=UTF-8
    // * the (source) clock won't know what time its starting with until the data streams have
    //   all been wired together (via the Injector)

    //add(AvailablityBias / Recency) -- see How to Think screenshot
    //add(ConfirmationBias)
    //add(TimeSpent)
    //add(Rating)
    //add(SearchRelevance)
  }
}
