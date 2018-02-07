package com.hamstoo.stream

import com.hamstoo.utils.{ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.duration.Duration


/**
  * A group/window/subset/projection defining a subset of datapoints to be grouped together for processing.
  * This is the "map" step in the map-reduce framework.
  * See also:
  *   https://softwaremill.com/windowing-data-in-akka-streams/
  *   https://gist.github.com/adamw/3803e2361daae5bdc0ba097a60f2d554
  */
trait Group
case class TimeWindow(begin: TimeStamp, end: TimeStamp) extends Group

/**
  * Functor to yield sets of groups for datapoints via `groupsFor` method.
  */
trait GroupFactory[G <: Group] {

  /** Computes the groups to which a given datapoint belongs. */
  def groupsFor[T](d: Datum[T]): Set[G]
}

class TimeWindowFactory(length: Duration, step: Duration) extends GroupFactory[TimeWindow] {

  val windowLength: TimeStamp = length.toMillis
  val windowStep: TimeStamp = step.toMillis
  val windowsPerEvent: Int = (windowLength / windowStep).toInt

  /** Computes the groups to which a given datapoint belongs. */
  override def groupsFor[T](d: Datum[T]): Set[TimeWindow] = {
    val ts = d.sourceTime
    val firstWindowStart = ts - ts % windowStep - windowLength + windowStep
    (0 until windowsPerEvent).toSet.map((i: Int) =>
      TimeWindow(firstWindowStart + i * windowStep, firstWindowStart + i * windowStep + windowLength))
  }
}

/**
  * Commands for when to initiate groups, when to add data to them, and when to close and reduce
  */
sealed trait GroupCommand[T] { def g: Group }
case class OpenGroup[T](g: Group) extends GroupCommand[T]
case class CloseGroup[T](g: Group) extends GroupCommand[T]
case class AddToGroup[T](d: Datum[T], g: Group) extends GroupCommand[T]

/**
  * Functor to yield open/close/add GroupCommands for datapoints via `forDatum` method.
  *
  * "The logic of the CommandGenerator is also the main point where you can customise how windowing of data is done:
  * how to define watermarks, how to extract timestamps from events, whether to use sliding or tumbling windows etc."
  *   https://softwaremill.com/windowing-data-in-akka-streams/
  */
abstract class GroupCommandFactory[G <: Group](groupFactory: GroupFactory[G]) {

  // mutable set of open groups ("Yes, you are right, there’s mutable state there! But you can think about it
  // as the internal state of an actor, then hopefully it won’t look so bad.")
  private val openGroups = mutable.Set[G]()

  /** Return the list of GroupCommands for the given datum. */
  def commandsFor[T](d: Datum[T]): List[GroupCommand[T]] // must be a List, not a Seq, as req'd by statefulMapConcat

  /**
    * Given a datum, compute the set of groups to which it belongs, and return a list of open/close/add GroupCommands.
    * @param d             The datum.
    * @param triggerClose  A function that should return true if an open group should be closed.  For example, the
    *                      TimeWindowCommandFactory closes groups when it's no longer possible that an existing
    *                      group could contain any future datapoints.
    */
  protected def commandsForInner[T](d: Datum[T], triggerClose: G => Boolean): List[GroupCommand[T]] = {

    // produce groups for this datum
    val datumGroups = groupFactory.groupsFor(d)

    // command these groups to close (e.g. their time has passed)
    val closeCommands = openGroups.filter(triggerClose).map { g => openGroups.remove(g); CloseGroup[T](g) }

    // command these groups to open (e.g. their time is just beginning)
    val openCommands = datumGroups.diff(openGroups).map { g => openGroups.add(g); OpenGroup[T](g) }

    // commands to add the datum to its `groupsFor`
    val addCommands = datumGroups.map(g => AddToGroup(d, g))

    openCommands.toList ++ closeCommands.toList ++ addCommands.toList
  }
}

/**
  * Applies high watermark logic on top of GroupCommandFactory.
  */
class TimeWindowCommandFactory(timeWindowFactory: TimeWindowFactory, maxDelay: Duration)
    extends GroupCommandFactory(timeWindowFactory) {

  val logger = Logger(classOf[TimeWindowCommandFactory])

  // see `openGroups` comment on mutable state above
  private var watermark = 0L

  /** Ensure datum delays are within acceptable bounds and, if so, generate group commands. */
  override def commandsFor[T](d: Datum[T]): List[GroupCommand[T]] = {

    // update high watermark
    watermark = math.max(watermark, d.sourceTime - maxDelay.toMillis)

    // if datum missed the boat for this window (and all future windows) then ignore it
    if (d.sourceTime < watermark) {
      logger.debug(s"Dropping datum with timestamp: ${d.sourceTime.dt}")
      Nil

    } else {
      // TODO: is it a problem that the group closing isn't triggered until a tick that's `maxDelay` after `end`?
      // or is this merely "allow[ing] us to keep the memory usage bounded"
      commandsForInner(d, (og: TimeWindow) => og.end < watermark)
    }
  }
}