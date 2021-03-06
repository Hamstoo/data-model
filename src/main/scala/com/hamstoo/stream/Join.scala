/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream.scaladsl.{FlowOps, GraphDSL}
import com.hamstoo.stream.Data.{Data, ExtendedData, dataOrdering}
import akka.stream.{Attributes, FanInShape2, FlowShape, Graph, Inlet, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.hamstoo.utils.{DurationMils, ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.mutable
import scala.collection.immutable
import scala.concurrent.duration._

/**
  * Combine the elements of multiple streams into a stream of joinerd elements using a joinerr function.
  * This `Join` class was modeled after `akka.stream.scaladsl.ZipWith`.
  *
  * TODO: similar to the `zipWith` transformer method we should have an (monkey patched) `join` (or `joinWith`) method
  *
  * Similar to the idea behind RESTful interfaces, we limit the number of ways (methods) of joining two
  * DataStreams to this single approach.  To achieve custom join behavior, EntityIds can be modified
  * to fit this method.  For most applications, however, this behavior is intended to be sufficient to
  * avoid forcing users to consider the leaky abstraction that is joining.
  *
  * '''Emits when''' all of the inputs have an element available
  *
  * '''Backpressures when''' downstream backpressures
  *
  * '''Completes when''' any upstream completes
  *
  * '''Cancels when''' downstream cancels
  */
object Join {

  val logger = Logger(Join.getClass)

  /**
    * Create a new `Join` specialized for 2 inputs.    *
    * @param joiner  joining-function from the input values to the output value
    */
  def apply[A0, A1, O](joiner: (A0, A1) => O,
                       pairwise: Join.Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                       expireAfter: DurationMils = DEFAULT_EXPIRE_AFTER): Join2[A0, A1, O] =
    new Join2(joiner, pairwise, expireAfter)

  /**
    * This class allows two streams a and b to be joined by calling `a.joinWith(b)` as opposed to manually wiring
    * them up with a `Source.fromGraph(GraphDSL.create() ... )`.
    *
    * See BIG NOTE below.
    *
    * The implementations here were originally derived from FlowOps.zipWith and zipWithGraph in
    * akka/stream/scaladsl/Flow.scala.
    *
    * `val imp` cannot be `private` because of `imp.Repr` being returned from `joinWith` which causes the following
    * compiler error: "private value imp escapes its defining scope as part of type
    * JoinWithable.this.imp.Repr[com.hamstoo.stream.Data.Data[O]]"
    *
    * Neither A0 nor O can be covariant types (e.g. +A0), as they are in FlowOps, because they both "occur in invariant
    * positions."
    */
  implicit class JoinWithable[-In, A0, +Mat](/*private*/ val imp: FlowOps[Data[A0], Mat]) {

    /**
      * BIG NOTE: Callers of this function will have to cast the returned instance to either a `Source[Data[O], Mat]` (if `imp`
      * is itself a `Source[Data[A0], Mat]`) or to a `Flow[In, Data[O], Mat]` (if `imp` is itself a `Flow`).  Since
      * `imp` is a FlowOps here, `imp.Repr` is the FlowOps version of Repr which gets overridden in both Source
      * and Flow.  I can't think of a better way to do this other than implementing duplicated implicit classes
      * for Source and Flow separately.
      *
      * Notice the `joiner` function's signature matches that of `Join2` below.
      */
    def joinWith[A1, O](that: Graph[SourceShape[Data[A1]], _])
                       (joiner: (A0, A1) => O,
                        pairwise: Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                        expireAfter: DurationMils = DEFAULT_EXPIRE_AFTER): imp.Repr[Data[O]] = {

      implicit val names: Option[(String, String)] = Some((streamName(imp), streamName(that)))
      logger.debug(s"Joining streams: '${streamName(imp)}' and '${streamName(that)}'")
      imp.via(joinWithGraph(that)(joiner, pairwise, expireAfter))
    }

    /** Employ the GraphDSL to construct the joined flow. */
    protected def joinWithGraph[A1, O, M]
                          (that: Graph[SourceShape[Data[A1]], M])
                          (joiner: (A0, A1) => O,
                           pairwise: Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                           expireAfter: DurationMils = DEFAULT_EXPIRE_AFTER)
                          (implicit names: Option[(String, String)] = None):
                                 Graph[  FlowShape[Data[A0] @uncheckedVariance, Data[O]], M] =
      GraphDSL.create(that) { implicit b => that_ =>
        import akka.stream.scaladsl.GraphDSL.Implicits._
        val join = b.add(new Join2[A0, A1, O](joiner, pairwise, expireAfter))
        that_ ~> join.in1
        FlowShape(join.in0, join.out)
      }
  }

  /** Default `expireAfter` duration. */
  val DEFAULT_EXPIRE_AFTER: DurationMils = (0 seconds).toMillis

  /** Like Budweiser, but more pairy. */
  type Pairwiser[A0, A1] = (Data[A0], Data[A1]) => Option[Pairwised[A0, A1]]

  /** Type returned by a Join's `pairwise` function. */
  case class Pairwised[A0, A1](paired: Data[(A0, A1)], consumed0: Boolean = false, consumed1: Boolean = false)

  /** Nothing fancy, merely constructs a pair of the two inputs, which has the same effect as `{ case x => x }`. */
  def DEFAULT_JOINER[A0, A1](a0: A0, a1: A1): (A0, A1) = (a0, a1)

  /** Default `pairwise` function.  The two returned Booleans indicate which of the two Data were fully consumed. */
  def DEFAULT_PAIRWISE[A0, A1](d0: Data[A0], d1: Data[A1]): Option[Pairwised[A0, A1]] = {

    // we don't care about knownTimes matching, only sourceTimes (see comment in Data.pairwise)
    Data.pairwise(d0, d1) match {
      case Nil => None

      case single :: Nil =>
        // an element was consumed if the ids are exactly equal (e.g. a MarkId("1234") paired with UnitId will
        // only result in the former being consumed)
        def consumed[T](x: Datum[T]): Boolean = x.id == single.id && x.sourceTime == single.sourceTime

        assert(consumed(d0.head) || consumed(d1.head))
        Some(Pairwised(immutable.Seq(single), consumed(d0.head), consumed(d1.head)))

      case paired =>
        // either a UnitId was paired up with each element of a Data, or the elements of two Data were paired
        // with each other, in either case they were both fully consumed
        Some(Pairwised(paired, consumed0 = true, consumed1 = true))
    }
  }
}

/**
  * `Join` specialized for 2 inputs.  Note the input streams must both emit `Data[A]`s.
  *
  * @param joiner       Joiner function that takes an A0 and an A1 as input and produces an O.
  * @param pairwise     Pairs up the values in Data[A0] with their respective values in Data[A1] into an
  *                     (optional) Data[(A0, A1)] (if they can indeed be joined) in preparation for joining by
  *                     the `joiner` function.
  * @param expireAfter  Unjoined elements will expire after this amount of time and thus never be joined.
  */
class Join2[A0, A1, O](val joiner: (A0, A1) => O,
                       val pairwise: Join.Pairwiser[A0, A1] = Join.DEFAULT_PAIRWISE[A0, A1] _,
                       expireAfter: DurationMils = Join.DEFAULT_EXPIRE_AFTER)
                      (implicit names: Option[(String, String)] = None)
    extends GraphStage[FanInShape2[Data[A0], Data[A1], Data[O]]] {

  val logger: Logger = Join.logger
  
  override def initialAttributes = Attributes.name("Join2")
  override val shape = new FanInShape2[Data[A0], Data[A1], Data[O]]("Join2")
  val in0: Inlet[Data[A0]] = shape.in0
  val in1: Inlet[Data[A1]] = shape.in1
  def out: Outlet[Data[O]] = shape.out

  /** Define the GraphStageLogic. */
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    // "It is very important to keep the GraphStage object itself immutable and reusable. All mutable state needs
    // to be confined to the GraphStageLogic that is created for every materialization."

    // it is essential to include all 3 (knownTime,sourceTime,id) fields of the Data in its Ordered.compare method
    // b/c if, for example, only knownTime is included then inserting two Datas with the same knownTime into one of
    // the sets below will silently fail/no-op; try it:
    //   val s = SortedSet.empty[(Int, Int)](Ordering.by(_._1))
    //   s += Tuple2(1,1) // res1: s.type = TreeSet((1,1))
    //   s += Tuple2(1,2) // res2: s.type = TreeSet((1,1))
    //def orderingBy[T](d: Data[T]) = (d.knownTime, d.sourceTime, d.id/*, d.value*/)

    // buffers of joinable (i.e. not yet fully joined nor expired) data for each input (the Orderings are merely
    // prudent, not required)
    val joinable0: mutable.Set[Data[A0]] = mutable.SortedSet.empty[Data[A0]](dataOrdering[A0])
    val joinable1: mutable.Set[Data[A1]] = mutable.SortedSet.empty[Data[A1]](dataOrdering[A1])

    /**
      * A Watermark class consisting of a (knownTime, sourceTime) pair.  This class is needed to handle when a bunch
      * of sourceTimes are all jammed together with the same knownTime.  In that case we defer to the sourceTime
      * portion of the Watermark to tell us which input port to pull from next.
      */
    case class Watermark(t_k: TimeStamp, t_s: TimeStamp) extends Ordered[Watermark] {
      override def compare(oth: Watermark): Int = if (t_k != oth.t_k) t_k.compare(oth.t_k) else t_s.compare(oth.t_s)
      override def toString: String = s"(${t_k.Gs},${t_s.Gs})"
      def updated(d: Data[_]): Watermark = {
        val d_k = d.knownTimeMax
        val d_s = d.sourceTimeMax
        Watermark(math.max(t_k, d_k - expireAfter), math.max(t_s, d_s))
      }
    }

    // high watermark timestamps for each input
    private var watermark0 = Watermark(-1L, -1L)
    private var watermark1 = Watermark(-1L, -1L)

    // "without this field the completion signalling would take one extra pull" and with it, if isAvailable(in) is
    // true, we can signal to the next call to pushOneMaybe to complete/stop, regardless of where that call originates
    var willShutDown0 = false
    var willShutDown1 = false

    private def completeJoin(s: String): Unit = {
      logger.debug(s"\u001b[33mcompleteJoin($s) (${namesStr.length})\u001b[0m$namesStr: sz0=${joinable0.size}, sz1=${joinable1.size}")
      completeStage()
    }

    /** This function implements the equivalent of this line `if (pending == 0) pushAll()` of ZipWith. */
    private def pushOneMaybe(): Unit = {

      // Remove expired elements from the sets before searching for joinable ones.  The watermarks have already been
      // adjusted for a delay of `expireAfter` so if there is any delay between sourceTimes and knownTimes, this
      // parameter value should be set larger.  Otherwise perfectly joinable sourceTime data may be dropped prematurely.
      //val sz0 = (joinable0.size, joinable1.size)
      Seq(joinable0, joinable1).foreach(_.retain(_.knownTimeMax >= math.min(watermark0.t_k, watermark1.t_k)))
      //val sz1 = (joinable0.size, joinable1.size)
      //if (sz0 != sz1) logger.warn(s"Sizes: $sz0 -> $sz1")

      // find a single joinable pair, if one exists and the out port is pushable (the view/headOption combo makes this
      // operate like a lazy find that stops iterating as soon as it finds a match)
      val pushable = joinable0.view.flatMap { d0: Data[A0] =>
        joinable1.view.flatMap { d1: Data[A1] =>

          (if (d0.isEmpty && d1.isEmpty) {
            logger.trace(s"  \u001b[33mempties (${namesStr.length})\u001b[0m$namesStr")
            Some(Join.Pairwised(Data.empty[(A0, A1)], true, true))
          }
           else pairwise(d0, d1))
            .map((d0, d1, _))

        }.headOption
      }.headOption

      pushable.foreach { case (d0, d1, Join.Pairwised(paired, consumed0, consumed1)) =>

        // convert each value from an (A0, A1) pair to an O
        val joined = paired.map(e => e.withValue(joiner(e.value._1, e.value._2)))

        // push to consumer, which should then pull again from this materialized Join instance, if ready;
        // `isAvailable(out)` can be tested for either here or in both of the `onPush`es
        //logger.debug(s"  pushing: ${joined.sourceTime.tfmt}, ${joined.id}, consumed=${(consumed0, consumed1)}")
        logger.trace(s"  \u001b[33mpushing (${namesStr.length})\u001b[0m$namesStr: ${shorten(d0)} + ${shorten(d1)} = ${shorten(joined)}, consumed=${(consumed0, consumed1)}")
        /*if (isAvailable(out))*/ push(out, joined) /*else emit(out, joined)*/

        // cleanup to ensure we don't perform the same join again in the future (i.e. remove one or both of the joinees)
        assert(consumed0 || consumed1)
        if (consumed0) joinable0.remove(d0)
        if (consumed1) joinable1.remove(d1)
      }

      // if no data got consumed then pull from whichever data source is lagging behind, the fact that no data
      // got consumed probably means that the lagging data source has yet to produce data that is joinable to
      // data that the leading data source has already produced (and is waiting in its respective `joinableX` set)
      /*if (willShutDown) completeStage()
      else if (pushable.isEmpty) {
        if (watermark0 > watermark1) pull(in1) else pull(in0)
      }*/

      // update: switched from a single willShutDown to two so that if in1 finishes we can still pull from in0
      // if it's behind giving it a chance to catch up to whatever's already in the joinable1 buffer (and vice versa)
      // update2: we cannot simply pull whichever port can be pulled as that could cause one of the streams to get
      // too far ahead (as would happen with a ThrottledSource, which is tested for in ClockTests.scala)
      val b0 = !hasBeenPulled(in0) && !isAvailable(in0) && !willShutDown0
      val b1 = !hasBeenPulled(in1) && !isAvailable(in1) && !willShutDown1
      if (watermark0 > watermark1) {
        if (willShutDown1) completeJoin("1") else if (pushable.isEmpty) { // else wait for next onPull
          logger.trace(s"pull(in1[$b1]) $namesStr: $watermark0 > $watermark1")
          if (b1) pull(in1)
        }
      } else if (watermark0 < watermark1) {
        if (willShutDown0) completeJoin("0") else if (pushable.isEmpty) { // else wait for next onPull
          logger.trace(s"pull(in0[$b0]) $namesStr: $watermark0 < $watermark1")
          if (b0) pull(in0)
        }
      } else {
        if (willShutDown0 && willShutDown1) completeJoin("X") else if (pushable.isEmpty) { // else wait for next onPull
          logger.trace(s"pull(in0[$b0] & in1[$b1]) $namesStr: $watermark0 == $watermark1")
          if (b0) pull(in0)
          if (b1) pull(in1)
        }
      }
    }

    /** InHandler 0 */
    setHandler(in0, new InHandler {

      /**
        * "onPush() is called when the input port has a new element. Now it is possible to acquire this element
        * using grab(in) and/or call pull(in) on the port to request the next element. It is not mandatory to grab
        * the element, but if it is pulled while the element has not been grabbed it will drop the buffered element.
        */
      override def onPush(): Unit = {
        val d: Data[A0] = grab(in0)
        joinable0 += d
        logger.trace(s"onPush0 $namesStr: ${shorten(d)}, sz=${joinable0.size}, wm=$watermark0")//, j=$joinable0")
        watermark0 = watermark0.updated(d)
        if (isAvailable(out)) pushOneMaybe()
      }

      /**
        * onUpstreamFinish of port in0 is (supposed to be) triggered by a pull(in0) when upstream has been exhausted.
        * However, it appears, rather, to be called *when downstream pulls* and upstream has been exhausted.  This is a
        * problem if there aren't any upstream elements at all (e.g. if they all get filtered out) because downstream
        * pulls are triggered by pushes from this instance.  So in order to trigger an onUpstreamFinish, we need to
        * first push so that downstream can receive and re-pull.
        *
        * This might not be a problem if the very first downstream pull wasn't necessary to commence the execution of
        * the entire stream graph.  In other words, the very first downstream pull happens before we know if there
        * are any elements or not, so onUpstreamFinish obviously can't be called then.  Another, later pull is still
        * required then, even if 0 elements have occurred, to trigger onUpstreamFinish.
        */
      override def onUpstreamFinish(): Unit = {
        logger.debug(s"\u001b[33monUpstreamFinish0 (${namesStr.length})\u001b[0m$namesStr (sz=${joinable0.size}&${joinable1.size}): if (${!isAvailable(in0)} && $watermark1 > $watermark0 || $willShutDown1)...")
        if (!isAvailable(in0) && (watermark1 > watermark0 || willShutDown1)) completeJoin("0")
        willShutDown0 = true
      }
    })

    /** InHandler 1 */
    setHandler(in1, new InHandler {

      override def onPush(): Unit = {
        val d: Data[A1] = grab(in1)
        joinable1 += d
        logger.trace(s"onPush1 $namesStr: ${shorten(d)}, sz=${joinable1.size}, wm=$watermark1")//, j=$joinable1")
        watermark1 = watermark1.updated(d)
        if (isAvailable(out)) pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"\u001b[33monUpstreamFinish1 (${namesStr.length})\u001b[0m$namesStr (sz=${joinable0.size}&${joinable1.size}): if (${!isAvailable(in1)} && $watermark0 > $watermark1 || $willShutDown0)...")
        if (!isAvailable(in1) && (watermark0 > watermark1 || willShutDown0)) completeJoin("1")
        willShutDown1 = true
      }
    })

    /**
      * OutHandler
      *
      * The documentation says this: "most Sinks would need to request upstream elements as soon as they are
      * created: this can be done by calling pull(inlet) in the preStart() callback."  The reason we don't have
      * to do that in this `Join` class is because it's not a Sink, it's a Flow, so we can wait for its
      * OutHandler to be onPulled to do so.
      *
      * Other documentation says this: "The first difference we can notice is that our Buffer stage is
      * automatically pulling its upstream on initialization.  The buffer has demand for up to two elements without
      * any downstream demand."  So perhaps we only need to signal demand in preStart() when a buffer has demand
      * before downstream does.
      */
    setHandler(out, new OutHandler {

      /**
        * "onPull() is called when the output port is ready to emit the next element, push(out, elem) is now
        * allowed to be called on this port.
        */
      override def onPull(): Unit = {
        logger.trace(s"\u001b[33monPull (${namesStr.length})\u001b[0m$namesStr")
        pushOneMaybe()
      }
    })
  }

  override def toString = "Join2"

  def namesStr: String = names.fold(s"[$in0, $in1]") { ns =>
    val x = s"[${ns._1}, ${ns._2}]"
    if (x.length == 28) "\u001b[35m" + x + "\u001b[0m" else x
  }

  def shorten(d: Seq[_]): String = {
    val str = d.toString
    if (str.length <= 100) str else str.take(70) + "..." + str.takeRight(27)
  }
}
