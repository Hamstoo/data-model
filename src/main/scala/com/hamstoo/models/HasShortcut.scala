package com.hamstoo.models

/**
  * Created by
  * Author: fayaz.sanaulla@gmail.com
  * Date: 24.09.17
  */

/**
  * Trait that implement shortcut functionality
  * @tparam A - Shortcut entity
  */
trait HasShortcut[A] {

  //todo: make A param more generic
  def shortcut: A
}
