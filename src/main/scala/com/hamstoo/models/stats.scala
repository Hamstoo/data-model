package com.hamstoo.models

case class Stats(
                  marks: Int,
                  imported: Int,
                  marksTotal: Int,
                  marksLatest: Seq[StatsDay],
                  marksLatestSum: Int,
                  mostProductive: StatsDay)

case class StatsDay(date: String, marks: Int)
