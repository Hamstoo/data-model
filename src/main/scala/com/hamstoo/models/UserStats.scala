/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.{ExtendedTimeStamp, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

/**
  * Instances of this class are not (as of 12/17) stored in the database as documents in a collection.
  * Instead these instances are constructed by the `UserStatDao.stats` method.  This is more of a frontend
  * API interface than a data model.
  *
  * @param nMarks          entries.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
  * @param nImported        Count of number of imported marks from the `imports` collection.
  * @param marksLatest     List of counts for each day over the last 4 weeks.
  * @param marksLatestSum  Total number over the last 4 weeks, computed from the `entries` collection.
  * @param mostProductive  Max nMarks day over last 4 weeks.
  * @param userVecSimMin   min(marksLatest.map(_.userVecSimilarity))
  * @param userVecSimMax   max(marksLatest.map(_.userVecSimilarity))
  */
case class ProfileDots(nMarks: Int,
                       nImported: Int,
                       marksLatest: Seq[ProfileDot],
                       marksLatestSum: Int,
                       mostProductive: ProfileDot,
                       userVecSimMin: Double = UserStats.DEFAULT_SIMILARITY,
                       userVecSimMax: Double = UserStats.DEFAULT_SIMILARITY,
                       autoGenKws: Option[String] = None,
                       confirmatoryKws: Option[String] = None,
                       antiConfirmatoryKws: Option[String] = None)

/**
  * A count of the number of marks that were created on a particular date.
  * @param date               The date on which the marks were created.
  * @param nMarks             The number of marks created on that date.
  * @param userVecSimilarity  The cosine similarity of the user's average vector to the marks' vectors from this day.
  */
case class ProfileDot(date: String, year: Int, nMarks: Int, userVecSimilarity: Double = UserStats.DEFAULT_SIMILARITY)

/**
  * Statistics corresponding to a user's aggregate marks, computed over time.
  *
  * @param userId   User who the stats are for.
  * @param ts       Time at which the stats were computed.
  * @param vectors  Vectors for this user at that time.  Same as `Representation.vectors`.
  * @param autoGenKws      Keywords selected from this users' marks that are most similar (see `documentSimilarity`)
  *                        to content this user typically marks.
  * @param recentAutoGenKws  Keywords computed from marks that are at most RECENT_KWS_DAYS_BACK days old.
  * @param confirmatoryKws Keywords that are most similar to content this user typically rates high and least
  *                        similar to content this user typically rates low--think "confirmation bias."
  */
case class UserStats(userId: UUID,
                     ts: TimeStamp,
                     vectors: Map[String, Representation.Vec],
                     autoGenKws: Option[Seq[String]] = None,
                     recentAutoGenKws: Option[Seq[String]] = None,
                     confirmatoryKws: Option[Seq[String]] = None,
                     antiConfirmatoryKws: Option[Seq[String]] = None) {

  override def toString: String =
    s"${getClass.getSimpleName}($userId, ${ts.tfmt}, nVectors=${vectors.size}, $autoGenKws, $recentAutoGenKws, $confirmatoryKws, $antiConfirmatoryKws)"
}

object UserStats extends BSONHandlers {

  // can't default to NaN (java.lang.NumberFormatException when passing to frontend)
  val DEFAULT_SIMILARITY = 0.0

  // UserStats.recentAutoGenKws get computed from marks at most this many days old
  val RECENT_KWS_DAYS_BACK = 7

  val USR: String = Mark.USR; assert(USR == nameOf[UserStats](_.userId))
  val TIMESTAMP: String = nameOf[UserStats](_.ts)
  val VECS: String = Representation.VECS; assert(VECS == nameOf[UserStats](_.vectors))
  val RECENT_AUTO_GEN_KWS: String = nameOf[UserStats](_.recentAutoGenKws)

  implicit val userStatsHandler: BSONDocumentHandler[UserStats] = Macros.handler[UserStats]
}