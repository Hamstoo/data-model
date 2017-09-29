package com.hamstoo.models

/**
  * A Stub is part of a data model that's required by the frontend.
  * @tparam A  The part of the data model to be returned by the pure virtual
  */
trait FrontendStub[A] {

  // todo: make A param more generic
  // if it's will be necessary, in future let's define separate file with all shortcut classes
  def stub: A
}
