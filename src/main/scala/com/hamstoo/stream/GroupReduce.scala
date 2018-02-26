package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source

/**
  * A stream reducer.  Doesn't reduce the whole stream, but rather groups of elements along it and reduces each
  * group into its own datapoint.
  */
object GroupReduce {

  // support for using a Seq here:
  //   http://blog.kunicki.org/blog/2016/07/20/implementing-a-custom-akka-streams-graph-stage/
  case class Dataset[T](group: Option[Group], data: Seq[Datum[T]])

  /**
    * Group the input DataStream into at most maxSubstreams, and then aggregate and reduce each one individually.
    *
    * @param dataSource  The Source[Datum[T]] to be group-reduced.
    * @param grouper  Tell me how to group incoming data from the stream, and I'll do it.  Note that this parameter
    *                 is a _function_ that produces group _commands_, not the groups themselves--nor a fish!
    * @param maxSubstreams  "upper bound on the number of sub-streams that will be open at any time"
    *                       "configures the maximum number of substreams (keys) that are supported; if more distinct
    *                       keys are encountered then the stream fails"
    *                       https://doc.akka.io/docs/akka/2.5/stream/stream-cookbook.html
    * @param reducer  The reducing function to apply to each group.
    * @tparam T  Data type
    * @tparam G  The type of group produced by the grouper's group commands.
    */
  def apply[T, G <: Group](dataSource: Source[Datum[T], NotUsed],
                           grouper: () => GroupCommandFactory[G],
                           maxSubstreams: Int)
                          (reducer: (Dataset[T]) => Datum[T]) {
    dataSource
      .statefulMapConcat { () => // each datum will map to a set of groups which it belongs to
        val factory = grouper() // TODO: how do we ensure grouper is calling `new` each time?
        d => factory.commandsFor(d) // bind the new factory into the returned func (don't call grouper() inside here!)
      }
      .groupBy(maxSubstreams, command => command.g)
      // "Subsequent combinators will be applied to _each_ of the sub-streams [separately]."
      .takeWhile(!_.isInstanceOf[CloseGroup[T]])
      .fold(Dataset(None, Seq.empty[Datum[T]])) {
        case (agg, OpenGroup(g)) => agg.copy(group = Some(g))
        case (agg, CloseGroup(_)) => agg // always filtered out by takeWhile, only here to prevent compiler error
        case (agg, AddToGroup(d, _)) => agg.copy(data = agg.data :+ d)
      }
      .map { g =>

        // TODO: find GCF EntityId
        EntityId.gcf(g.data.map(_.id))


        Datum(, g.data.map(_.sourceTime).min, g.data.map(_.knownTime).max, reducer(g.data.map(_.value)))
      }
      .mergeSubstreams
  }
}
