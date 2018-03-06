package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.hamstoo.utils.ExtendedTimeStamp
import play.api.Logger

/**
  * A stream reducer.  Doesn't reduce the whole stream, but rather groups of elements along it and reduces each
  * group into its own datapoint.
  */
object GroupReduce {

  val logger = Logger(GroupReduce.getClass.getName)

  // support for using a Seq here:
  //   http://blog.kunicki.org/blog/2016/07/20/implementing-a-custom-akka-streams-graph-stage/
  case class GroupData[T](group: Option[Group], data: Seq[Data[T]])

  /**
    * Group the input DataStream into at most maxSubstreams, and then aggregate and reduce each one individually.
    *   https://softwaremill.com/windowing-data-in-akka-streams/
    *
    * @param dataSource  The Source[Data[T] ] to be group-reduced.
    * @param grouper  This parameter is a factory function that constructs a new GroupCommandFactory "each time the
    *                 stream will be materialized."  The constructed GroupCommandFactory gets bound into a closure
    *                 that generates group _commands_, not the groups themselves, for each streamed Data[T].
    * @param maxSubstreams  "upper bound on the number of sub-streams that will be open at any time"
    *                       "configures the maximum number of substreams (keys) that are supported; if more distinct
    *                       keys are encountered then the stream fails"
    *                       https://doc.akka.io/docs/akka/2.5/stream/stream-cookbook.html
    * @param reducer  The reducing function to apply to each group.
    * @tparam T  Data type
    * @tparam G  The type of group produced by the grouper's group commands.
    */
  def apply[T, G <: Group](dataSource: Source[Data[T], NotUsed],
                           grouper: () => GroupCommandFactory[G],
                           maxSubstreams: Int = 64)
                          (reducer: (Seq[T]) => T): Source[Data[T], NotUsed] =
    dataSource

      // "The no-argument function provided to statefulMapConcat will be called each time the stream will be
      // materialized" so every Data[T] that is streamed as part of the same materialization will share the same
      // GroupCommandFactory with its own state
      .statefulMapConcat { () => // each datum will map to a set of groups which it belongs to
        val factory = grouper() // construct a new GroupCommandFactory for this materialization
        data => factory.commandsFor(data) // bind the new factory into a closure (don't call grouper() inside here!)
      }

      // "Subsequent combinators will be applied to _each_ of the sub-streams [separately]."
      .groupBy(maxSubstreams, command => command.g)

      // tell the sub-streams when to finish
      .takeWhile(!_.isInstanceOf[CloseGroup[T]])

      // fold will emit its final value "when takeWhile encounters a CloseWindow (or when the whole stream completes)"
      .fold(GroupData(None, Seq.empty[Data[T]])) {
        case (agg, OpenGroup(g)) =>
          assert(agg.data.isEmpty)
          logger.debug(s"OpenGroup($g)")
          agg.copy(group = Some(g))
        case (agg, CloseGroup(_)) =>
          logger.debug(s"CloseGroup(_)")
          agg // always filtered out by takeWhile, only here to prevent compiler error
        case (agg, AddToGroup(d, g)) =>
          assert(agg.group.nonEmpty)
          logger.debug(s"AddToGroup(${d.knownTime}, $g)")
          agg.copy(data = agg.data :+ d)
      }

      // "put an asynchronous boundary around each sub-flow? covers the entire sub-flow... up to groupBy...
      // This is entirely optional, if sub-stream processing is fast, you might want to drop the .async"
      //.async

      .map { g =>
        assert(g.group.nonEmpty && g.data.nonEmpty)

        val knownTime = g.data.map(_.knownTime).max

        val values: Map[EntityId, SourceValue[T]] = g.group.get.longitudinal match {

          // longitudinal (across time) aggregation
          case Some(true) =>
            // pivot from Data[T].values (which each include every entity ID) to Seq[T] for each entity ID
            val entityIds = g.data.flatMap(_.values.keys).toSet
            entityIds.par.map { id =>
              val svs: Seq[SourceValue[T]] = g.data.flatMap(_.values.get(id))
              id -> SourceValue(reducer(svs.map(_.value)), svs.map(_.sourceTime).max)
            }.toMap.seq

          // cross-sectional (across entities) aggregation (or both cross-sectional/longitudinal combined)
          case _ =>
            val svs: Seq[SourceValue[T]] = g.data.flatMap(_.values.values)
            val reducedVal = reducer(svs.map(_.value))
            logger.debug(s"Cross-sectional reduce at ${knownTime.dt}: $reducedVal")
            Map(UnitId() -> SourceValue(reducedVal, svs.map(_.sourceTime).max))
        }

        Data(knownTime, values)
      }
      .mergeSubstreams
}
