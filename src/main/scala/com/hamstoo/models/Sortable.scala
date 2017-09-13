package com.hamstoo.models

/**
  * Trait that must be extended for sorting by pageCoord field
  */
trait Sortable {
  val pageCoord: PageCoord
}

object Sortable {

  /**
    * Function-predicate that sort 2 sortable entity by pageCoord param in decreasing order.
    * First sort by 'y' param, then if they are equal, trying to make comparision by `x` param of PageCoord class.
    *
    * @param firstEntity - first entity
    * @param secondEntity - second entity
    * @return
    */
  def sortByPageCoord(firstEntity: Sortable, secondEntity: Sortable): Boolean = (firstEntity, secondEntity) match {
    case (c1, c2) if c1.pageCoord.y > c2.pageCoord.y => true
    case (c1, c2) if c1.pageCoord.y == c2.pageCoord.y && c1.pageCoord.x > c2.pageCoord.x => true
    case _ => false
  }
}
