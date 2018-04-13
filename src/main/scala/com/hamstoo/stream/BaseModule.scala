/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import com.google.inject.multibindings.OptionalBinder
import com.google.inject.{AbstractModule, Key}
import net.codingwell.scalaguice.ScalaModule
import play.api.Logger

/**
  * "A module is a collection of bindings"
  * "The modules are the building blocks of an injector, which is Guice's object-graph builder."
  */
class BaseModule extends AbstractModule with ScalaModule {

  import BaseModule._

  // having an `implicit this` enables the `:=` methods of InjectId and this class, StreamModule
  implicit val implicitThis: BaseModule = this

  /**
    * An overloaded assignment operator of sorts--or as close as you can get in Scala.  Who remembers Pascal?
    * Example: `classOf[ExecutionContext] := system.dispatcher`
    */
  implicit class InjectVal[T :Manifest](private val _typ: Class[T]) /*extends AnyVal*/ {
    def :=(instance: T): Unit = new NamelessInjectId[T] := instance
    def ?=(default: T): Unit = new NamelessInjectId[T] ?= default
    def :=[TImpl <:T :Manifest](clazz: Class[TImpl]): Unit = bind[T].to[TImpl]
  }

  /**
    * Bind a (possibly named) instance given its (type, name) pair, which Guice uses to uniquely identify bindings.
    *
    * Using `OptionalBinder.setBinding` here instead of simply calling `bind` because the former works when
    * `OptionalBinder.setDefault` has been called previously while the latter does not.
    */
  def assign[T :Manifest](key: Key[T], instance: T): Unit = {
    logger.debug(s"Binding $key to instance: $instance")
    //bind(key).toInstance(instance) // see ScalaDoc above for why not doing this
    OptionalBinder.newOptionalBinder(binder(), key).setBinding().toInstance(instance)
  }

  /** Bind an optional injectable argument with a default value. */
  def assignOptional[T :Manifest](key: Key[T], default: T): Unit = {
    logger.debug(s"Binding (optional) $key to default: $default")
    OptionalBinder.newOptionalBinder(binder(), key).setDefault().toInstance(default)
  }
}

object BaseModule {
  val logger = Logger(classOf[BaseModule])
}
