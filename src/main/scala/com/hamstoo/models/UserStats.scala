package com.hamstoo.models

/**
  * Instances of this class are not (as of 12/17) stored in the database as documents in a collection.
  * Instead these instances are constructed by the `MongoUserStatsDao.stats` method.
  *
  * @param nMarks          entries.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
  * @param imported        Count of number of imported marks from the `imports` collection.
  * @param marksLatest     List of counts for each day over the last 4 weeks.
  * @param marksLatestSum  Total number over the last 4 weeks, computed from the `entries` collection.
  * @param mostProductive  Max nMarks day over last 4 weeks.
  * @param userVecSimMin   min(marksLatest.map(_.userVecSimilarity))
  * @param userVecSimMax   max(marksLatest.map(_.userVecSimilarity))
  */
case class UserStats(nMarks: Int,
                     imported: Int,
                     marksLatest: Seq[UserStatsDay],
                     marksLatestSum: Int,
                     mostProductive: UserStatsDay,
                     userVecSimMin: Double = Double.NaN,
                     userVecSimMax: Double = Double.NaN)

/**
  * A count of the number of marks that were created on a particular date.
  * @param date               The date on which the marks were created.
  * @param nMarks             The number of marks created on that date.
  * @param userVecSimilarity  The cosine similarity of the user's average vector to the marks' vectors from this day.
  */
case class UserStatsDay(date: String, nMarks: Int, userVecSimilarity: Double = Double.NaN)
