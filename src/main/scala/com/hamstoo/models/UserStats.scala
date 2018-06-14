/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.TimeStamp
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Instances of this class are not (as of 12/17) stored in the database as documents in a collection.
  * Instead these instances are constructed by the `UserStatDao.stats` method.
  *
  * @param nMarks          entries.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
  * @param imported        Count of number of imported marks from the `imports` collection.
  * @param marksLatest     List of counts for each day over the last 4 weeks.
  * @param marksLatestSum  Total number over the last 4 weeks, computed from the `entries` collection.
  * @param mostProductive  Max nMarks day over last 4 weeks.
  * @param userVecSimMin   min(marksLatest.map(_.userVecSimilarity))
  * @param userVecSimMax   max(marksLatest.map(_.userVecSimilarity))
  */
case class ProfileDots(nMarks: Int,
                       imported: Int,
                       marksLatest: Seq[ProfileDot],
                       marksLatestSum: Int,
                       mostProductive: ProfileDot,
                       userVecSimMin: Double = Double.NaN,
                       userVecSimMax: Double = Double.NaN)

/**
  * A count of the number of marks that were created on a particular date.
  * @param date               The date on which the marks were created.
  * @param nMarks             The number of marks created on that date.
  * @param userVecSimilarity  The cosine similarity of the user's average vector to the marks' vectors from this day.
  */
case class ProfileDot(date: String, nMarks: Int, userVecSimilarity: Double = Double.NaN)

/**
  * Statistics corresponding to a user's aggregate marks, computed over time.
  *
  * @param userId   User who the stats are for.
  * @param ts       Time at which the stats were computed.
  * @param vectors  Vectors for this user at that time.  Same as `Representation.vectors`.
  */
case class UserStats(userId: UUID,
                     ts: TimeStamp,
                     vectors: Map[String, Representation.Vec])

object UserStats extends BSONHandlers {

  val USR: String = Mark.USR; assert(USR == nameOf[UserStats](_.userId))
  val TIMESTAMP: String = nameOf[UserStats](_.ts)
  val VECS: String = Representation.VECS; assert(VECS == nameOf[UserStats](_.vectors))

  implicit val userStatsHandler: BSONDocumentHandler[UserStats] = Macros.handler[UserStats]
}