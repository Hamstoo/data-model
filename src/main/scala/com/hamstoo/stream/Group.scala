package com.hamstoo.stream

import com.hamstoo.utils.{ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * A group/window/subset/projection defining a subset of datapoints to be grouped together for processing.
  * This is the "map" step in the map-reduce framework.
  * See also:
  *   https://softwaremill.com/windowing-data-in-akka-streams/
  *   https://gist.github.com/adamw/3803e2361daae5bdc0ba097a60f2d554
  */
sealed abstract class Group(val end: TimeStamp) { def longitudinal: Option[Boolean] = None }
case class TimeWindow(begin: TimeStamp, end1: TimeStamp) extends Group(end1) {
  override val longitudinal = Some(true)
  override def toString: String = s"${getClass.getSimpleName}(${begin.tfmt}, ${end.tfmt})"
}
case class CrossSection(ts: TimeStamp) extends Group(ts) { override val longitudinal = Some(false) }
case class TimeWinCrossSec(begin: TimeStamp, end1: TimeStamp) extends Group(end1)

/**
  * Functor to yield sets of groups for datapoints via `groupsFor` method.
  */
trait GroupFactory[G <: Group] {

  /** Computes the groups to which a given datapoint belongs. */
  def groupsFor[T](d: Datum[T]): Set[G]
}

/**
  * A GroupFactory that generates sliding window groups over time.
  * @param length  Time window length.
  * @param step    Step between consecutive time window starts--should be smaller than `length`.
  */
class TimeWindowFactory(length: Duration, step: Duration) extends GroupFactory[TimeWindow] {

  val windowLength: TimeStamp = length.toMillis
  val windowStep: TimeStamp = step.toMillis
  val windowsPerEvent: Int = (windowLength / windowStep).toInt

  /** Computes the groups to which a given datapoint belongs. */
  override def groupsFor[T](d: Datum[T]): Set[TimeWindow] = {
    val ts = d.knownTime
    val firstWindowStart = ts - ts % windowStep - windowLength + windowStep
    (0 until windowsPerEvent).toSet.map((i: Int) =>
      TimeWindow(firstWindowStart + i * windowStep, firstWindowStart + i * windowStep + windowLength))
  }
}

/**
  * A GroupFactory that generates groups for cross-sections (orthogonal to time) of data.
  */
class CrossSectionFactory() extends GroupFactory[CrossSection] {
  override def groupsFor[T](d: Datum[T]) = Set(CrossSection(d.knownTime))
}

/**
  * Commands for when to initiate groups, when to add data to them, and when to close and reduce
  */
sealed trait GroupCommand[T] { def g: Group }
case class OpenGroup[T](g: Group) extends GroupCommand[T]
case class CloseGroup[T](g: Group) extends GroupCommand[T]
case class AddToGroup[T](d: Datum[T], g: Group) extends GroupCommand[T]

/**
  * Functor to yield open/close/add GroupCommands for datapoints via `forData` method.
  *
  * "The logic of the CommandGenerator is also the main point where you can customise how windowing of data is done:
  * how to define watermarks, how to extract timestamps from events, whether to use sliding or tumbling windows etc."
  *   https://softwaremill.com/windowing-data-in-akka-streams/
  */
abstract class GroupCommandFactory[G <: Group](groupFactory: GroupFactory[G]) {

  val logger = Logger(classOf[GroupCommandFactory[G]])

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

    // produce new groups for this data
    val newGroups = groupFactory.groupsFor(d)
    logger.debug(s"groupsFor($d) = $newGroups")

    // open-add-close ordering is important (see comment below)

    // command these (nonexistent) groups to open (e.g. their time is just beginning)
    val openCommands = newGroups.diff(openGroups).map { g => openGroups.add(g); OpenGroup[T](g) }

    // command these groups to add the new Datum[T]
    val addCommands = newGroups.map(g => AddToGroup(d, g))

    // command these groups to close (e.g. their time has passed)
    val closeCommands = openGroups.filter(triggerClose).map { g => openGroups.remove(g); CloseGroup[T](g) }

    // must perform in this order to ensure that groups can be opened-added-closed in the same loop
    logger.debug(s"nCommands: open=${openCommands.size} add=${addCommands.size} close=${closeCommands.size}")
    openCommands.toList ++ addCommands.toList ++ closeCommands.toList
  }
}

/**
  * Applies high watermark logic on top of GroupCommandFactory.
  */
abstract class HighWatermarkCommandFactory[G <: Group, F <: GroupFactory[G]](timeWindowFactory: F, maxDelay: Duration)
    extends GroupCommandFactory[G](timeWindowFactory) {

  // see `openGroups` comment on mutable state above
  private var watermark = 0L

  val maxDelayMils: TimeStamp = maxDelay.toMillis

  /** Ensure datum delays are within acceptable bounds and, if so, generate group commands. */
  override def commandsFor[T](d: Datum[T]): List[GroupCommand[T]] = {

    // if datum missed the boat for this window (and all future windows) then ignore it
    if (d.knownTime < watermark) {
      logger.warn(s"Dropping excessively delayed data: ${d.knownTime.dt} < ${(watermark + maxDelayMils).dt} - ${maxDelayMils}ms")
      Nil

    } else {

      // if `maxDelayMils == 0` then we're computing a cross-sectional group and if `d.values.size > 1` then all of
      // the data for this group should reside inside one single Datum[T] (as opposed to a series of Datum[T]), so we
      // immediately close the group; o/w wait until we see data with timestamps past the high watermark, even in the
      // case of cross-sectional which could also be be arriving one Datum[T] at a time
      //val bCrossSecData: Boolean = maxDelayMils == 0 && d.values.size > 1
      // update (2018.4.12): cross-sectional, multi-value Data has been retired in favor of single-value Datum only
      val bCrossSecData: Boolean = maxDelayMils == 0 && false

      // update high watermark
      watermark = math.max(watermark, d.knownTime - maxDelayMils + (if (bCrossSecData) 1 else 0))

      // TODO: is it a problem that the group closing isn't triggered until a tick that's `maxDelay` after `end`?
      // TODO: or is this merely "allow[ing] us to keep the memory usage bounded"
      val triggerClose = (g: G) => bCrossSecData || g.end < watermark
      commandsForInner(d, triggerClose)
    }
  }
}

/**
  * A GroupCommandFactory that constructs GroupCommands for sliding windows of data over time.
  * @param timeWindowFactory  Defines window length and step size between the windows.
  * @param maxDelay           Allows data to arrive late and out of order.  Only after this delay has passed will
  *                           a group be closed and triggered for processing--see `triggerClose`.
  */
class TimeWindowCommandFactory(timeWindowFactory: TimeWindowFactory, maxDelay: Duration)
  extends HighWatermarkCommandFactory[TimeWindow, TimeWindowFactory](timeWindowFactory, maxDelay)

/**
  * A 0-time-width GroupCommandFactory that, when operating on Data[T], issues CloseGroup commands in tandem with
  * each Open/AddGroup command pair.  When operating on Datum[T] it has to wait until the first data item following
  * the group in order to trigger it to close.
  */
class CrossSectionCommandFactory
  extends HighWatermarkCommandFactory[CrossSection, CrossSectionFactory](new CrossSectionFactory(), 0 millis)