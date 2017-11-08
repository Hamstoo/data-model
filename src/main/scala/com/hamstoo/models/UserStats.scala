package com.hamstoo.models

/**
  * Instances of this class are not stored in the database as documents in a collection.  Instead these instances
  * are constructed by the `MongoUserStatsDao.stats` method.
  *
  * @param marks           entries.count(Some(d :~ Mark.USR -> userId.toString :~ Mark.TIMETHRU -> INF_TIME))
  * @param imported        Count of number of imported marks from the `imports` collection.
  * @param marksTotal      Total number from the `userstats` collection.
  * @param marksLatest     List of counts for each day over the last 4 weeks.
  * @param marksLatestSum  Total number over the last 4 weeks, computed from the `userstats` collection.
  * @param mostProductive  Max day over last 4 weeks.
  */
case class UserStats(
                  marks: Int,
                  imported: Int,
                  marksTotal: Int,
                  marksLatest: Seq[UserStatsDay],
                  marksLatestSum: Int,
                  mostProductive: UserStatsDay)

/** A count of the number of marks that were created on a particular date. */
case class UserStatsDay(date: String, marks: Int)
