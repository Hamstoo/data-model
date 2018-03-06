package com.hamstoo.models

/**
  * Trait marked of position instances
  */
trait Positions

object Positions {

  /**
    * If two Annotations start in the same node, and thus have identical page coordinates, then resort to their
    * Positions to determine sort order.
    */
  def sort(a: Positions, b: Positions): Boolean = (a, b) match {

    // use highlight head node start index to order two highlights
    case (a1: Highlight.Position, b1: Highlight.Position) =>
      a1.elements.headOption.fold(0)(_.index) <= b1.elements.headOption.fold(0)(_.index)

    // use inline note coordinates to order two inline notes
    case (a1: InlineNote.Position, b1: InlineNote.Position) =>
      PageCoord.sort(Some(a1.nodeCoord), Some(b1.nodeCoord)).getOrElse(true)

    // i guess we'll put highlights before inline notes--can't think of any sensible way to compare their Positions
    case (_: Highlight.Position, _) => true
    case _ => false
  }
}
