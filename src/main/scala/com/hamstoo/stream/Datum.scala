/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import com.hamstoo.utils.{ExtendedString, ExtendedTimeStamp, ObjectId, TimeStamp}

import scala.annotation.tailrec
import scala.collection.immutable

/**
  * Entity IDs are either an ObjectId/String paired with a type label, e.g. ReprId, or a collection of such
  * items, e.g. CompoundId.  A UnitId is another special kind of entity ID that doesn't correspond to any
  * particular entity, but which is joinable to all of them--e.g. think an average value computed cross-sectionally
  * for all entities in the universe at a particular point in time.
  */
trait EntityId extends Ordered[EntityId] {
  // EntityIds need to be orderable so that Datum.compare can include them so that Join.joinable0/1 SortedSets work OK
  def compare(oth: EntityId): Int = CompoundId(this).toSortedString.compare(CompoundId(oth).toSortedString)
}

case object UnitId extends EntityId
case class MarkId(id: ObjectId) extends EntityId
case class ReprId(id: ObjectId) extends EntityId
case class FacetName(name: String) extends EntityId
case class CompoundId(ids: Set[EntityId]) extends EntityId {
  // must map(_.toString) first b/c EntityIds can't o/w be sorted
  def toSortedString: String = s"${getClass.getSimpleName}(${ids.map(_.toString).toSeq.sorted})"
}

object EntityId {

  /**
    * If two (compound) entity IDs match on all of the dimensions that they both share, then they are joinable.
    * Returns the EntityId with higher cardinality because this is what the joined data should be labeled with.
    */
  @tailrec
  def join(eid0: EntityId, eid1: EntityId): Option[EntityId] = (eid0, eid1) match {
    case (a: CompoundId, b: CompoundId) =>
      val (smaller, larger) = if (a.ids.size < b.ids.size) (a.ids, b.ids) else (b.ids, a.ids)
      if (smaller != a.ids.intersect(b.ids)) None else larger.size match {
        case 0 => Some(UnitId)
        case 1 => larger.headOption
        case _ => Some(CompoundId(larger))
      }
    case (a, b) => join(CompoundId(a), CompoundId(b))
  }

  /** Merge two entity IDs together, de-duplicating any dimensions that they share. */
  @tailrec
  def merge(eid0: EntityId, eid1: EntityId): EntityId = (eid0, eid1) match {
    case (a: CompoundId, b: CompoundId) =>
      val union = a.ids.union(b.ids)
      union.size match {
        case 0 => UnitId
        case 1 => union.head
        case _ => CompoundId(union)
      }
    case (a, b) => merge(CompoundId(a), CompoundId(b))
  }
}

object CompoundId {

  /** Construct a CompoundId from other types of EntityIds. */
  def apply(eid: EntityId): CompoundId = eid match {
    case x: CompoundId => x
    case UnitId => CompoundId(Set.empty[EntityId])
    case x => CompoundId(Set(x))
  }
}

/**
  * A `Datum` (singular) is a (single) datapoint/value corresponding to some entity at some point in time.  The
  * data type is determined by the collection or stream that holds the data set to which the datum belongs.
  *
  * @param id           Entity ID to which this datum belongs
  * @param sourceTime   Source/event/producer time when datum was generated by its source
  * @param knownTime    Ingestion/processing/consumer/compute/known time when datum became known to the receiving
  *                     system or when it was modified by the system.  Data sources should typically load their
  *                     data according to the Clock and put "known time" here, but for processing purposes (e.g.
  *                     cross-sectional aggregation) a function might want to pivot sourceTime into this field.
  *                     http://blog.colinbreck.com/considering-time-in-a-streaming-data-system/
  * @param value        The value of this datapoint
  */
case class Datum[+T](value: T, id: EntityId, sourceTime: TimeStamp, knownTime: TimeStamp) {

  override def toString: String = this match {
    case Tick(time) => s"Tick(${time.tfmt})"
    case _ => s"${getClass.getSimpleName}(${value.toString.shorten()}, $id, $sourceTime, $knownTime)"
  }

  /** Same Datum, different value. */
  def withValue[A](newValue: A): Datum[A] = Datum(newValue, id, sourceTime, knownTime)
  def mapValue[A](f: T => A): Datum[A] = withValue(f(value))
}

object Datum {

  /** Shortcut if the two timestamps are the same. */
  def apply[T](value: T, id: EntityId, ts: TimeStamp): Datum[T] = new Datum(value, id, ts, ts)

  /** Join two `Data`s.  They should probably have the same `knownTime`s, but that is not enforced. */
  def pairwise[A0, A1](d0: Datum[A0], d1: Datum[A1]): Option[Datum[(A0, A1)]] = {

    // sourceTimes must be equal; it doesn't make sense to add today's stock price to yesterday's without
    // lagging first (the lagging will change the sourceTime b/c in that case the lag _is_ the source and the source
    // doesn't generate its data until the lag occurs; the lagging doesn't change the knownTime b/c we'll know
    // tomorrow's 1-day lagged data today, the same day as we know today's data, b/c it's the same data!)
    if (d0.sourceTime != d1.sourceTime) None
    else EntityId.join(d0.id, d1.id).map { jid =>
      Datum[(A0, A1)]((d0.value, d1.value), jid, d0.sourceTime, math.max(d0.knownTime, d1.knownTime))
    }
  }
}

/**
  * Plural; used by all the ops so that they don't have to be implemented to operate on both Datum and Data.
  * Plus, streaming batches of Data is just faster than individual Datum.
  */
object Data {
  type Data[+TT] = immutable.Seq[Datum[TT]]

  def empty[T] = immutable.Seq.empty[Datum[T]]

  def apply[T](data: Datum[T]*): Data[T] = data.to[immutable.Seq]

  implicit class ExtendedData[X](private val x: Data[X]) extends AnyVal {

    /** `maxBy` inconveniently throws an exception when its passed sequence is empty. */
    def maxOrElse[Y :Ordering](default: => Y)(f: Datum[X] => Y): Y =
      if (x.isEmpty) default else x.map(f).max // also see VecFunctions.maxOption, which only operates on Doubles

    def knownTimeMax: TimeStamp = x.maxOrElse(0L)(_.knownTime)
    def sourceTimeMax: TimeStamp = x.maxOrElse(0L)(_.sourceTime)
    def idMax: EntityId = x.maxOrElse(UnitId.asInstanceOf[EntityId])(_.id)
  }

  /** Cartesian product of all joinable pairs of elements in the two datasets. */
  def pairwise[A0, A1](d0: Data[A0], d1: Data[A1]): Data[(A0, A1)] =
    (for(e0 <- d0; e1 <- d1) yield Datum.pairwise(e0, e1)).flatten

  /** This can't be an `implicit object` typeclass because it has to take type parameters. */
  implicit def dataOrdering[T]: Ordering[Data[T]] = new Ordering[Data[T]] { // convert to Single Abstract Method?

    /**
      * Ordering[Data[_]] interface.  If there are multiple (knownTime,sourceTime,id) 3-tuples with different values
      * the behavior is (justifiably) undefined.  In addition, `value` is not included in this method, nor should
      * it be, lest it would have to be orderable.  The SortedSets in Join require Datum to be orderable.
      */
    override def compare(a: Data[T], b: Data[T]): Int = {
      val (kt_a, st_a, id_a) = (a.knownTimeMax, a.sourceTimeMax, a.idMax)
      val (kt_b, st_b, id_b) = (b.knownTimeMax, b.sourceTimeMax, b.idMax)
      if (kt_a != kt_b) kt_a.compare(kt_b) else if (st_a != st_b) st_a.compare(st_b) else id_a.compare(id_b)
    }
  }
}

/**
  * A Tick is just an alias for a Datum[TimeStamp], like any other Datum[T], but with bounds on its
  * value, sourceTime, and knownTime.  Namely, they must all be equal.
  */
class Tick(private val _time: TimeStamp, private val _previousTime: TimeStamp)
    extends Datum[TimeStamp](_time, UnitId, _time, _time) {

  // accessors
  def time: TimeStamp = _time
  def previousTime: TimeStamp = _previousTime
}

object Tick {

  def apply(time: TimeStamp, previousTime: TimeStamp): Tick = new Tick(time, previousTime)

  def unapply[T](dat: Datum[T]): Option[TimeStamp] =
    if (dat.id == UnitId && dat.value.isInstanceOf[TimeStamp] &&
        dat.value == dat.sourceTime) Some(dat.value.asInstanceOf[TimeStamp]) else None
}