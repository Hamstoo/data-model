package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.SearchStats.{Click, FacetVal}
import com.hamstoo.utils.{ObjectId, TIME_NOW, TimeStamp, generateDbId}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Database entry model for recording search results popularity respective to their search queries. The 'map'
  * fields use list types for better translation to JSON.
  *
  * @param userId  User who initiated the search.
  * @param markId  The mark that was clicked.
  * @param query   The search query string for which statistics are recorded.
  * @param facets  If any search facets were used to tailor the query (e.g. label:books).
  * @param clicks  The "stats"; the things the user clicked as a result the search.
  */
case class SearchStats(id: ObjectId = generateDbId(Mark.ID_LENGTH),
                       userId: UUID,
                       markId: ObjectId,
                       query: String,
                       facets: Set[FacetVal] = Set.empty[FacetVal],
                       clicks: Seq[Click] = Seq.empty[Click])

/**
  * Extending BSONHandlers allows UUID to be converted properly for Reactive Mongo.  Otherwise one of the following
  * errors can occur:
  *
  *   MongoSearchStatsDao.scala:41:22: type mismatch;
  *   [error]  found   : (String, java.util.UUID)
  *   [error]  required: reactivemongo.bson.Producer[reactivemongo.bson.BSONElement]
  *
  *   SearchStats.scala:50:85: Implicit not found 'userId': reactivemongo.bson.BSONReader[_, java.util.UUID]
  */
object SearchStats extends BSONHandlers {

  type ClickType = Int
  val URL_CLICK: ClickType = 0
  val FPV_CLICK: ClickType = 1

  /**
    * Each click on a mark from the My Marks page will generate one of these.
    * @param clickType  If the mark's URL is clicked this will be 0.  If it's FPV is clicked, this will be 1.
    * @param relevance  The relevance to the search terms.  This is the value used to order the search results.
    * @param index      The index of this mark in the results.
    * @param ts         Timestamp of when the click occurred.
    */
  case class Click(clickType: ClickType, relevance: Double, index: Int, ts: TimeStamp = TIME_NOW)

  /**
    * Really just a key-value pair to make facet storage as flexible as possible.
    */
  case class FacetVal(name: String, value: Double)

  val ID: String = com.hamstoo.models.Mark.ID;  assert(nameOf[SearchStats](_.id) == ID)
  val USR: String = com.hamstoo.models.Mark.USR;  assert(nameOf[SearchStats](_.userId) == USR)
  val MARKID: String = nameOf[SearchStats](_.markId)
  val QUERY: String = nameOf[SearchStats](_.query)
  implicit val clickHandler: BSONDocumentHandler[Click] = Macros.handler[Click]
  implicit val facetValHandler: BSONDocumentHandler[FacetVal] = Macros.handler[FacetVal]
  implicit val searchStatsHandler: BSONDocumentHandler[SearchStats] = Macros.handler[SearchStats]
}
