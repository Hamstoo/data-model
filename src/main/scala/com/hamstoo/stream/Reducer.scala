package com.hamstoo.stream

/**
  * A stream reducer.  Doesn't reduce the whole stream, but rather groups of elements along it and reduces each
  * group into its own datapoint.  Note that this class does not extend DataStream, but rather its `apply`
  * function returns one.
  *
  * @param grouper  Tell me how to group incoming data from the stream, and I'll do it.  Note that this parameter
  *                 is a _function_ that produces group _commands_, not the groups themselves.
  * @param maxSubstreams  "upper bound on the number of sub-streams that will be open at any time"
  *                       "configures the maximum number of substreams (keys) that are supported; if more distinct
  *                       keys are encountered then the stream fails"
  *                       https://doc.akka.io/docs/akka/2.5/stream/stream-cookbook.html
  * @tparam T  Data type
  * @tparam G  The type of group produced by the grouper's group commands.
  */
abstract class Reducer[T, G <: Group](grouper: () => GroupCommandFactory[G], maxSubstreams: Int) {

  case class Dataset(group: Option[Group], data: Seq[Datum[T]])

  /** Implement me! */
  protected def reduce(data: Dataset): Datum[T]

  /** Group the input DataStream into at most maxSubstreams, and then aggregate and reduce each one individually. */
  def apply(ds: DataStream[T]): DataStream[T] = {

    val src = ds.source

      // each datum will map to a set of groups which it belongs to
      .statefulMapConcat { () =>
        val factory = grouper() // TODO: how do we ensure grouper is calling `new` each time?
        d => factory.commandsFor(d) // bind the new factory into the returned function
      }
      .groupBy(maxSubstreams, command => command.g)
                                // "Subsequent combinators will be applied to _each_ of the sub-streams [separately]."
      .takeWhile(!_.isInstanceOf[CloseGroup[T]])
      .fold(Dataset(None, Seq.empty[Datum[T]])) {
        case (agg, OpenGroup(g))     => agg.copy(group = Some(g))
        case (agg, CloseGroup(_))    => agg // always filtered out by takeWhile, only here to prevent compiler error
        case (agg, AddToGroup(d, _)) => agg.copy(data = agg.data :+ d)
      }
      .map(reduce)
      .mergeSubstreams

    // wrap it up in a DataStream so that it can be used in future DataStream ops
    DataStreamRef(src)
  }

}
