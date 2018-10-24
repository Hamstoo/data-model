/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, BSONObjectID, Macros}

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
  * @param _id     "recommended solution is to provide an id yourself, using BSONObjectID.generate"
  *                  https://stackoverflow.com/questions/39353496/get-id-after-insert-with-reactivemongo
  */
case class Recommendation(userId: UUID,
                          source: String,
                          params: Map[String, String],
                          url: String,
                          subj: String = "",
                          ts: TimeStamp = TIME_NOW,
                          _id: BSONObjectID = BSONObjectID.generate,
                          var versions: Option[Map[String, String]] = None) {

  // same technique as is used for Representations
  versions = Some(versions.getOrElse(Map.empty[String, String])
                    .updated("data-model", Option(getClass.getPackage.getImplementationVersion).getOrElse("null")))

  /**
    * Search terms, if they exist (e.g. the generation method may not have utilized search terms at all), that were
    * used to generate the recommendation.
    */
  def searchTerms: Set[String] =
    params.getOrElse(Recommendation.PARAM_SEARCH_TERMS, "").split(" ").map(_.trim.toLowerCase).filterNot(_.isEmpty).toSet

  /**
    * Initial backend impl of My Recs page is copied from My Marks page, so using the same data structure also.
    * `id` field is not used, but if user hovers over Subject in UI then it is visible as the link destination.
    */
  def toMark: Mark = {
    val ri = ReprInfo("", ReprType.PUBLIC, expRating = Some(_id.stringify)) // allows for expRating lookup in db
    val md = MarkData(s"$subj [$source/${searchTerms.mkString(",")}]", Some(url), tags = Some(Set(MarkData.RECOMMENDATION_TAG)), recId = Some(_id))
    Mark(userId, id = "mark-it", mark = md, timeFrom = ts, reprs = Seq(ri))
  }
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
