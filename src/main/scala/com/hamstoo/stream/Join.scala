package com.hamstoo.stream

import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import play.api.Logger

import scala.collection.mutable
import scala.concurrent.duration._

/**
  * Combine the elements of multiple streams into a stream of combined elements using a combiner function.
  * This `Join` class was modeled after `akka.stream.scaladsl.ZipWith`.
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
  def apply[A1, A2, O](joiner: (A1, A2) => O): Join2[A1, A2, O] =
    new Join2(joiner)

}

/**
  * `Join` specialized for 2 inputs.
  *
  * @param joiner       Joiner function that takes an A1 and an A2 as input and produces an O.
  * @param expireAfter  Unjoined elements will expire after this amount of time, and thus never be joined.
  */
class Join2[A1, A2, O](val joiner: (A1, A2) => O,
                       expireAfter: FiniteDuration = 0 seconds)
    extends GraphStage[FanInShape2[Datum[A1], Datum[A2], Datum[O]]] {

  val logger = Logger(classOf[Join2[A1, A2, O]])
  
  override def initialAttributes = Attributes.name("Join2")
  override val shape = new FanInShape2[Datum[A1], Datum[A2], Datum[O]]("Join2")
  def out: Outlet[Datum[O]] = shape.out
  val in0: Inlet[Datum[A1]] = shape.in0
  val in1: Inlet[Datum[A2]] = shape.in1

  /** Define the GraphStageLogic. */
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    // "It is very important to keep the GraphStage object itself immutable and reusable. All mutable state needs
    // to be confined to the GraphStageLogic that is created for every materialization."
    val joinable0: mutable.Set[Datum[A1]] = mutable.Set.empty[Datum[A1]]
    val joinable1: mutable.Set[Datum[A2]] = mutable.Set.empty[Datum[A2]]

    private var watermark0 = -1L
    private var watermark1 = -1L

    // without this field the completion signalling would take one extra pull
    var willShutDown = false

    /** This function implements the equivalent of this line `if (pending == 0) pushAll()` of ZipWith. */
    private def pushOneMaybe(): Unit = {

      // remove expired elements from the sets before searching for joinable ones
      Seq(joinable0, joinable1).foreach(_.retain(_.sourceTime >= math.min(watermark0, watermark1)))

      // find a joinable pair, if one exists (the view/headOption combo makes this operate like a lazy find)
      val tup = joinable0.view.flatMap { d0 =>
        joinable1.view.flatMap { d1 =>
          if (d0.sourceTime != d1.sourceTime) None           // source times must match
          else EntityId.join(d0.id, d1.id).map((d0, d1, _))  // and entity IDs must be joinable
        }.headOption
      }.headOption

      tup.foreach { case (d0, d1, joined) =>
        val d = Datum(joined, d0.sourceTime, math.max(d0.knownTime, d1.knownTime), joiner(d0.value, d1.value))
        logger.debug(s"  pushing: ${d0.id} + ${d1.id} = $d")
        push(out, d)

        // cleanup to ensure we don't perform the same join again in the future, i.e. remove one or both of the joinees
        Seq(joinable0, joinable1).foreach(_.retain(x => !(x.id == joined && x.sourceTime == d0.sourceTime)))
      }

      // if no data got consumed then pull from whichever data source is lagging behind, the fact that no data
      // got consumed probably means that the lagging data source has yet to produce data that is joinable to
      // data that the leading data source has already produced (and is waiting in its respective `joinableX` set)
      if (willShutDown) completeStage()
      else if (tup.isEmpty) {
        if (watermark0 > watermark1) pull(in1) else pull(in0)
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
        val d: Datum[A1] = grab(in0)
        joinable0 += d
        logger.debug(s"onPush0: $d -> ${joinable0.size} -> $watermark0")
        watermark0 = math.max(watermark0, d.sourceTime - expireAfter.toMillis) // update high watermark
        pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish0")
        if (!isAvailable(in0)) completeStage()
        willShutDown = true
      }
    })

    /** InHandler 1 */
    setHandler(in1, new InHandler {

      override def onPush(): Unit = {
        val d: Datum[A2] = grab(in1)
        joinable1 += d
        logger.debug(s"onPush1: $d -> ${joinable1.size} -> $watermark1")
        watermark1 = math.max(watermark1, d.sourceTime - expireAfter.toMillis) // update high watermark
        pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish1")
        if (!isAvailable(in1)) completeStage()
        willShutDown = true
      }
    })

    /**
      * OutHandler
      * The documentation says this: "most Sinks would need to request upstream elements as soon as they are
      * created: this can be done by calling pull(inlet) in the preStart() callback."  The reason we don't have
      * to do that in this `Join` class is because it's not a Sink, it's a Flow, so we can wait for its
      * OutHandler to be onPulled to do so.
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
