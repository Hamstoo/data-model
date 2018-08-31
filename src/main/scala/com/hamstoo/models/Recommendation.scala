/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import play.api.libs.json.{JsObject, Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * A user recommendation object.  Recommendations are one source of content for a user's feed.
  *
  * @param userId  User's UUID
  * @param source  One of the values from RecSourceEnum (as a String).
  * @param params  Details about what generated the recommendation (e.g. recent auto-generated keywords).
  * @param url     A URI where the recommendation is hosted.
  *                Note in the future we might want this to be optional, if for example, we're recommending marks
  *                and we want to put a markId in the subj field.
  * @param subj    The "title" tag of the URL (named `subj` to exemplify similarity to MarkData).
  * @param ts      The date when the recommendation was saved
  */
case class Recommendation(userId: UUID,
                          source: String,
                          params: Map[String, String],
                          url: String,
                          subj: String = "",
                          ts: TimeStamp = TIME_NOW) {

  /**
    * Search terms, if they exist (e.g. the generation method may not have utilized search terms at all), that were
    * used to generate the recommendation.
    */
  def searchTerms: Set[String] =
    params.getOrElse(Recommendation.PARAM_SEARCH_TERMS, "").split(" ").map(_.trim.toLowerCase).filterNot(_.isEmpty).toSet

  /** Used by backend's MarksController. */
  import RecommendationFormatters._
  def toJson: JsObject = Json.toJson(this).asInstanceOf[JsObject] - Recommendation.USR
}

object Recommendation extends BSONHandlers {

  // field names for DAO usage
  val USR      : String =      Mark.USR;        assert(USR       == nameOf[Recommendation](_.userId))
  val TIMESTAMP: String = UserStats.TIMESTAMP;  assert(TIMESTAMP == nameOf[Recommendation](_.ts))

  // arbitrary parameter names to be used as keys in Recommendation.params maps
  val PARAM_QUERY_TYPE = "queryType"
  val PARAM_SEARCH_TERMS: String = nameOf[Recommendation](_.searchTerms)

  implicit val recommendationHandler: BSONDocumentHandler[Recommendation] = Macros.handler[Recommendation]
}

object RecommendationFormatters {
  implicit val recommendationJson: OFormat[Recommendation] = Json.format[Recommendation]
}