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
  * @param joiner
  * @param expireAfter
  * @tparam A1
  * @tparam A2
  * @tparam O
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

    private var watermark = 0L

    // without this field the completion signalling would take one extra pull
    var willShutDown = false

    /** This function implements the equivalent of this line `if (pending == 0) pushAll()` of ZipWith. */
    private def pushOneMaybe(): Unit = {

      // remove expired elements from the sets before searching for joinable ones
      // TODO: if elements are pushed faster from upstream than they can be consumed by downstream will this
      // TODO:   cause us to drop elements before they have the chance to be joined?
      val (s0, s1) = (joinable0.size, joinable1.size)
      Seq(joinable0, joinable1).foreach(_.retain(_.sourceTime >= watermark))
      logger.debug(s"  pushOneMaybe A: $s0->${joinable0.size} $s1->${joinable1.size}")

      // find a joinable pair, if one exists
      val tup = joinable0.view.flatMap { d0 =>
        joinable1.view.flatMap { d1 =>
          EntityId.join(d0.id, d1.id)                     // entity IDs must be joinable
            .filter(_ => d0.sourceTime == d1.sourceTime)  // and source times must match
            .map((d0, d1, _))
        }.headOption
      }.headOption

      // TODO: why not just push all that are ready?
      tup.foreach { case (d0, d1, joined) =>
        val d = Datum(joined, d0.sourceTime, math.max(d0.knownTime, d1.knownTime), joiner(d0.value, d1.value))
        logger.debug(s"Pushing: $d, ${d0.id}, ${d1.id}")
        push(out, d)

        // cleanup to ensure we don't perform the same join again in the future
        val (s0, s1) = (joinable0.size, joinable1.size)
        Seq(joinable0, joinable1).foreach(_.retain(x => !(x.id == joined && x.sourceTime == d0.sourceTime)))
        logger.debug(s"  pushOneMaybe B: $s0->${joinable0.size} $s1->${joinable1.size}")
      }

      /*if (willShutDown) completeStage()
      else {
        pull(in0)
        pull(in1)
      }*/
    }

    /**
      * Prime the pump: "most Sinks would need to request upstream elements as soon as they are created: this can
      * be done by calling pull(inlet) in the preStart() callback."
      *
      * But this isn't a Sink, it's a Flow!  But why wouldn't the same apply to a ZipWith then?
      */
    override def preStart(): Unit = {
      logger.debug(s"preStart")
      pull(in0)
      pull(in1)
    }

    /** InHandler 0 */
    setHandler(in0, new InHandler {
      override def onPush(): Unit = {
        pull(in0)
        val d: Datum[A1] = grab(in0)
        logger.debug(s"onPush0: $d")
        joinable0 += d
        watermark = math.max(watermark, d.sourceTime - expireAfter.toMillis) // update high watermark
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
        pull(in1)
        val d: Datum[A2] = grab(in1)
        logger.debug(s"onPush1: $d")
        joinable1 += d
        watermark = math.max(watermark, d.sourceTime - expireAfter.toMillis) // update high watermark
        pushOneMaybe()
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish1")
        if (!isAvailable(in1)) completeStage()
        willShutDown = true
      }
    })

    /** OutHandler 1 */
    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        logger.debug(s"onPull")
        pushOneMaybe()
      }
    })
  }

  override def toString = "Join2"
}
