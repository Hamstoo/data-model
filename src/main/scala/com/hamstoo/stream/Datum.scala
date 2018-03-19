package com.hamstoo.stream

import com.hamstoo.utils.{ObjectId, TimeStamp}
import org.joda.time.DateTime
import play.api.libs.json.Json
import spire.tailrec

/**
  * Entity IDs are either an ObjectId/String paired with a type label, e.g. ReprId, or a collection of such
  * items, e.g. CompoundId.  A UnitId is another special kind of entity ID that doesn't correspond to any
  * particular entity, but which is joinable to all of them--e.g. think an average value computed cross-sectionally
  * for all entities in the universe at a particular point in time.
  */
trait EntityId
case class UnitId() extends EntityId
case class MarkId(id: ObjectId) extends EntityId
case class ReprId(id: ObjectId) extends EntityId
case class FacetName(name: String) extends EntityId
case class CompoundId(ids: Set[EntityId]) extends EntityId

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
        case 0 => Some(UnitId())
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
        case 0 => UnitId()
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
    case _: UnitId => CompoundId(Set.empty[EntityId])
    case x => CompoundId(Set(x))
  }
}

/** A `SourceValue` is a value along with the time that it was generated by/at its source. */
case class SourceValue[T](value: T, sourceTime: TimeStamp)

/**
  * A `Data` (plural) is a bunch of datapoints corresponding to a bunch of entities at some point in time.
  */
case class Data[T](knownTime: TimeStamp, values: Map[EntityId, SourceValue[T]]) {

  def oid: Option[EntityId] = if (values.size == 1) values.headOption.map(_._1) else None
  def oval: Option[SourceValue[T]] = if (values.size == 1) values.headOption.map(_._2) else None

  override def toString: String = {
    val vstr = s"$values"
    s"${getClass.getSimpleName}($knownTime, ${vstr.take(150)}${if (vstr.length > 150) "...." else ""})"
  }
}

/**
  * A `Datum` (singular) is a (single) datapoint corresponding to some entity at some point in time.  The
  * data type is determined by the collection or stream that holds the data set to which the datum belongs.
  *
  * @param id           Entity ID to which this datum belongs
  * @param sourceTime   Source/event/producer time when datum was generated by its source
  * @param computeTime (TODO: rename) Ingestion/processing/consumer/compute/known time when datum became known to the receiving
  *                     system or when it was modified by the system.  Data sources should typically load their
  *                     data according to the Clock and put "known time" here, but for processing purposes (e.g.
  *                     cross-sectional aggregation) a function might want to pivot sourceTime into this field.
  *                     http://blog.colinbreck.com/considering-time-in-a-streaming-data-system/
  * @param value        The value of this datapoint
  */
class Datum[T](id: EntityId, sourceTime: TimeStamp, knownTime: TimeStamp, value: T)
  extends Data[T](knownTime, Map(id -> SourceValue(value, sourceTime)))

object Datum {

  /** Shortcut if the two timestamps are the same. */
  def apply[T](id: EntityId, ts: TimeStamp, value: T): Datum[T] = new Datum(id, ts, ts, value)

  // TODO: switch around the order of constructor parameters for consistency's sake
  def apply[T](id: EntityId, sourceTime: TimeStamp, knownTime: TimeStamp, value: T): Datum[T] =
    new Datum(id, sourceTime, knownTime, value)
}

//class Tick(val time: TimeStamp) extends Datum[TimeStamp](UnitId(), time, time, time)

object Tick {
  type Tick = Data[TimeStamp]

  //import com.hamstoo.stream.Datum.Tick
  def apply(time: TimeStamp): Tick = Datum[TimeStamp](UnitId(), time, time, time)

  implicit class ExtendedTick(private val tick: Tick) extends AnyVal {
    def time: TimeStamp = tick.oval.get.value
  }
}

object Data {

  /** Join two `Data`s.  They should probably have the same `knownTime`s, but that is not enforced. */
  def join[A0, A1](d0: Data[A0], d1: Data[A1]): Option[Data[(A0, A1)]] = {

    val joinedValues = (for {
      (id0, v0) <- d0.values
      (id1, v1) <- d1.values
    } yield {
      EntityId.join(id0, id1).map(_ -> SourceValue[(A0, A1)]((v0.value, v1.value),
                                                             math.max(v0.sourceTime, v1.sourceTime)))
    }).flatten

    if (joinedValues.isEmpty) None else Some(Data(math.max(d0.knownTime, d1.knownTime), joinedValues.toMap))
  }

  /**
    * Group data by knownTime--especially useful if D type is Datum.  But note that behavior is unpredictable if
    * there are duplicate values for entityId-knownTime-sourceTime triples, which would be a pretty odd data source.
    */
  def groupByKnownTime[T/*, D <: Data[T]*/](data: Traversable[Data[T]]): Traversable[Data[T]] = {
    data.groupBy(_.knownTime).map { case (knownTime, iter) =>
      Data(knownTime, iter.flatMap(_.values.toSeq).toMap)
    }
  }
}

