package com.hamstoo.models

/**
  * Trait that implement shortcut functionality
  * @tparam A - Shortcut entity
  */
trait HasShortcut[A] {

  //todo: make A param more generic
  // if it's will be necessary, in future let's define separate file with all shortcut classes
  def shortcut: A
}
