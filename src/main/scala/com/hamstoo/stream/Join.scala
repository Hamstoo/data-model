package com.hamstoo.stream

import akka.stream.scaladsl.{FlowOps, GraphDSL}
import akka.stream.{Attributes, FanInShape2, FlowShape, Graph, Inlet, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.hamstoo.utils.TimeStamp
import play.api.Logger

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.mutable
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

  /**
    * Create a new `Join` specialized for 2 inputs.    *
    * @param joiner  joining-function from the input values to the output value
    */
  def apply[A0, A1, O](joiner: (A0, A1) => O,
                       pairwise: Join.Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                       expireAfter: Duration = DEFAULT_EXPIRE_AFTER): Join2[A0, A1, O] =
    new Join2(joiner, pairwise, expireAfter)

  /**
    * This class allows two streams a and b to be joined by calling `a.joinWith(b)` as opposed to manually wiring
    * them up with a `Source.fromGraph(GraphDSL.create() ... )`.
    *
    * The implementations here were mostly copied from FlowOps.zipWith and zipWithGraph in
    * akka/stream/scaladsl/Flow.scala.
    *
    * `val imp` cannot be `private` because of `imp.Repr` being returned from `joinWith` which causes the following
    * compiler error: "private value imp escapes its defining scope as part of type
    * JoinWithable.this.imp.Repr[com.hamstoo.stream.Data[O]]"
    *
    * Neither A0 nor O can be covariant types (e.g. +A0) as they are in FlowOps because they both "occur in invariant
    * positions."
    */
  implicit class JoinWithable[-In, A0, +Mat](/*private*/ val imp: FlowOps[Data[A0], Mat]) {

    /**
      * Callers of this function will have to cast the returned instance to either a `Source[Data[O], Mat]` (if `imp`
      * is itself a `Source[Data[A0], Mat]`) or to a `Flow[In, Data[A0], Mat]` (if `imp` is itself a `Flow`).  Since
      * `imp` is a FlowOps here, `imp.Repr` is the FlowOps version of Repr which gets overridden in both Source
      * and Flow.  I can't think of a better way to do this other than implementing duplicated implicit classes
      * for Source and Flow separately.
      *
      * Notice the `joiner` function's signature matches that of `Join2` below.
      */
    def joinWith[A1, O](that: Graph[SourceShape[Data[A1]], _])
                       (joiner: (A0, A1) => O,
                        pairwise: Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                        expireAfter: Duration = DEFAULT_EXPIRE_AFTER): imp.Repr[Data[O]] =
      imp.via(joinWithGraph(that)(joiner, pairwise, expireAfter))

    /** Employ the GraphDSL to construct the joined flow. */
    protected def joinWithGraph[A1, O, M]
                          (that: Graph[SourceShape[Data[A1]], M])
                          (joiner: (A0, A1) => O,
                           pairwise: Pairwiser[A0, A1] = DEFAULT_PAIRWISE[A0, A1] _,
                           expireAfter: Duration = DEFAULT_EXPIRE_AFTER):
                                 Graph[  FlowShape[Data[A0] @uncheckedVariance, Data[O]], M] =
      GraphDSL.create(that) { implicit b => that_ =>
        import akka.stream.scaladsl.GraphDSL.Implicits._
        val join = b.add(new Join2[A0, A1, O](joiner, pairwise, expireAfter))
        that_ ~> join.in1
        FlowShape(join.in0, join.out)
      }
  }

  /** Default `expireAfter` duration. */
  val DEFAULT_EXPIRE_AFTER: Duration = 0 seconds

  /** Like Budweiser, but more pairy. */
  type Pairwiser[A0, A1] = (Data[A0], Data[A1]) => Option[Pairwised[A0, A1]]

  /** Type returned by a Join's `pairwise` function. */
  case class Pairwised[A0, A1](paired: Data[(A0, A1)], consumed0: Boolean = false, consumed1: Boolean = false)

  /** Default `pairwise` function.  The two returned Booleans indicate which of the two Data were fully consumed. */
  def DEFAULT_PAIRWISE[A0, A1](d0: Data[A0], d1: Data[A1]): Option[Pairwised[A0, A1]] = {
    if (d0.knownTime != d1.knownTime) None       // known times must match
    else Data.pairwise(d0, d1).map { dPaired =>  // and at least one entity ID must be joinable
      dPaired.values.size match {

        case 0 => assert(false)
          Pairwised(dPaired)

        // either joining single-element, non-UnitId Datum w/ UnitId or w/ multi-element Data
        case 1 =>
          val idPaired = dPaired.oid.get
          def consumed[T](x: Data[T]): Boolean = x.oid.contains(idPaired) // and we already know the knownTimes match
          assert(consumed(d0) || consumed(d1))
          Pairwised(dPaired, consumed(d0), consumed(d1))

        // either joining multi-element Data with another multi-element or a UnitId (assume this knownTime is complete)
        case _ =>
          Pairwised(dPaired, consumed0 = true, consumed1 = true)
      }
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
                       expireAfter: Duration = Join.DEFAULT_EXPIRE_AFTER)
    extends GraphStage[FanInShape2[Data[A0], Data[A1], Data[O]]] {

  val logger = Logger(classOf[Join2[A0, A1, O]])
  
  override def initialAttributes = Attributes.name("Join2")
  override val shape = new FanInShape2[Data[A0], Data[A1], Data[O]]("Join2")
  val in0: Inlet[Data[A0]] = shape.in0
  val in1: Inlet[Data[A1]] = shape.in1
  def out: Outlet[Data[O]] = shape.out

  /** Define the GraphStageLogic. */
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    // "It is very important to keep the GraphStage object itself immutable and reusable. All mutable state needs
    // to be confined to the GraphStageLogic that is created for every materialization."

    // buffers of joinable (i.e. not yet fully joined nor expired) data for each input (the Orderings are merely
    // prudent, not required)
    val joinable0: mutable.Set[Data[A0]] = mutable.SortedSet.empty[Data[A0]](Ordering.by(_.knownTime))
    val joinable1: mutable.Set[Data[A1]] = mutable.SortedSet.empty[Data[A1]](Ordering.by(_.knownTime))

    // high watermark timestamps for each input
    private var watermark0: TimeStamp = -1L
    private var watermark1: TimeStamp = -1L

    // "without this field the completion signalling would take one extra pull" and with it, if isAvailable(in) is
    // true, we can signal to the next call to pushOneMaybe to complete/stop, regardless of where that call originates
    var willShutDown0 = false
    var willShutDown1 = false

    /** This function implements the equivalent of this line `if (pending == 0) pushAll()` of ZipWith. */
    private def pushOneMaybe(): Unit = {

      // remove expired elements from the sets before searching for joinable ones
      Seq(joinable0, joinable1).foreach(_.retain(_.knownTime >= math.min(watermark0, watermark1)))

      // find a single joinable pair, if one exists (the view/headOption combo makes this operate like a lazy find)
      val pushable = joinable0.view.flatMap { d0 =>
        joinable1.view.flatMap { d1 => pairwise(d0, d1).map((d0, d1, _)) }.headOption
      }.headOption

      // perhaps this has the same effect as the above; is it easier to read?
      //val pushable = (for(d0 <- joinable0.view; d1 <- joinable1.view)
        //yield pairwise(d0, d1).map((d0, d1, _))).headOption

      pushable.foreach { case (d0, d1, Join.Pairwised(dPaired, consumed0, consumed1)) =>

        // convert each value from an (A0, A1) pair to an O
        val dJoined = Data(dPaired.knownTime,
                           dPaired.values.mapValues(v => SourceValue(joiner(v.value._1, v.value._2), v.sourceTime)))

        // push to consumer, which should pull again from this materialized Join instance, if ready
        logger.debug(s"  pushing: $d0 + $d1 = $dJoined")
        push(out, dJoined)

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
      if (watermark0 > watermark1) {
        if (willShutDown1) completeStage() else if (pushable.isEmpty) pull(in1) // else wait for next onPull
      } else {
        if (willShutDown0) completeStage() else if (pushable.isEmpty) pull(in0) // else wait for next onPull
      }

    }

    /** InHandler 0 */
    setHandler(in0, new InHandler {

      /**
        * "onPush() is called when the input port has now a new element. Now it is possible to acquire this element
        * using grab(in) and/or call pull(in) on the port to request the next element. It is not mandatory to grab
        * the element, but if it is pulled while the element has not been grabbed it will drop the buffered element.
        */
      override def onPush(): Unit = {
        val d: Data[A0] = grab(in0)
        joinable0 += d
        logger.debug(s"onPush0: $d -> ${joinable0.size} -> $watermark0")
        watermark0 = math.max(watermark0, d.knownTime - expireAfter.toMillis) // update high watermark
        pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish0")
        if (!isAvailable(in0) && watermark1 > watermark0) completeStage()
        willShutDown0 = true
      }
    })

    /** InHandler 1 */
    setHandler(in1, new InHandler {

      override def onPush(): Unit = {
        val d: Data[A1] = grab(in1)
        joinable1 += d
        logger.debug(s"onPush1: $d -> ${joinable1.size} -> $watermark1")
        watermark1 = math.max(watermark1, d.knownTime - expireAfter.toMillis) // update high watermark
        pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish1")
        if (!isAvailable(in1) && watermark0 > watermark1) completeStage()
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
        logger.debug(s"onPull")
        pushOneMaybe()
      }
    })
  }

  override def toString = "Join2"
}
