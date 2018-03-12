package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.{Flow, GraphDSL, Source, ZipWith}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.hamstoo.stream.Tick.{ExtendedTick, Tick}
import com.hamstoo.utils.{ExtendedTimeStamp, TimeStamp}
import play.api.Logger

import scala.collection.mutable

/**
  * Throttle the elements of a DataSource per ticks of a Clock so that the `knownTime`s of the elements never
  * get ahead of the clock ticks.
  *
  * '''Emits when''' clock ticks bypass `knownTime`s of pre-loaded DataSource data
  */
object ClockThrottle {

  /**
    * Create a new `ClockThrottle` Flow.
    * @param clock  The `Clock` that will regulate the throttling.
    */
  def apply[T]()(implicit clock: Clock): Flow[Data[T], Data[T], NotUsed] = Flow.fromGraph {
    GraphDSL.create() { implicit builder =>
      val throttler = builder.add(new ClockThrottle[T])
      import GraphDSL.Implicits._
      clock.source ~> throttler.in0
      FlowShape(throttler.in1, throttler.out)
    }.named("ClockThrottle.apply")
  }.buffer(1, OverflowStrategy.backpressure)
}

class ClockThrottle[T] extends GraphStage[FanInShape2[Tick, Data[T], Data[T]]] {

  val logger = Logger(classOf[ClockThrottle[T]])

  override def initialAttributes = Attributes.name("ClockThrottle.name")
  override val shape = new FanInShape2[Tick, Data[T], Data[T]]("ClockThrottle.shape")
  val clockInlet: Inlet[Tick] = shape.in0
  val dataInlet: Inlet[Data[T]] = shape.in1
  def outlet: Outlet[Data[T]] = shape.out

  /** Define the GraphStageLogic. */
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    // "It is very important to keep the GraphStage object itself immutable and reusable. All mutable state needs
    // to be confined to the GraphStageLogic that is created for every materialization."
    val ordering: Ordering[Data[T]] = Ordering.by[Data[T], TimeStamp](_.knownTime)
    //val ordering1: Ordering[Data[T]] = Ordering[TimeStamp].on(_.knownTime) // https://www.scala-lang.org/api/2.12.3/scala/math/Ordering.html
    val buffer: mutable.Set[Data[T]] = mutable.SortedSet.empty[Data[T]](ordering)

    private var watermark = -1L

    // https://doc.akka.io/docs/akka/2.5.5/scala/stream/stream-customize.html#using-attributes-to-affect-the-behavior-of-a-stage
    private var downstreamWaiting = false

    // without this field the completion signalling would take one extra pull
    var willShutDown = false

    /** Returns true if a push occurs, which can only happen if `downstreamWaiting` is true. */
    private def pushOneMaybe(): Boolean = {

      // find a emitable buffer element, if one exists (we can use `headOption` b/c `buffer` is a SortedSet)
      val opt = buffer.headOption.filter(_.knownTime < watermark && downstreamWaiting)

      opt.foreach { d =>

        // push to consumer, which should pull again from this materialized ClockThrottle instance, if ready
        push(outlet, d)
        buffer -= d
        downstreamWaiting = false
      }

      if (willShutDown) { completeStage(); true } else opt.nonEmpty
    }

    /** Clock InHandler */
    setHandler(clockInlet, new InHandler {

      /**
        * "onPush() is called when the input port has now a new element. Now it is possible to acquire this element
        * using grab(in) and/or call pull(in) on the port to request the next element. It is not mandatory to grab
        * the element, but if it is pulled while the element has not been grabbed it will drop the buffered element.
        */
      override def onPush(): Unit = {
        val tick: Tick = grab(clockInlet)
        watermark = math.max(watermark, tick.time) // update high (clock) watermark
        logger.debug(s"onPush[clock]: ${tick.time.dt} -> ${buffer.size} -> $watermark")

        // TODO: if dataInlet doesn't have any data to onPush then don't we need to pull(clockInlet) again somewhere
        if (!pushOneMaybe()) {
          // TODO: but calling it here can cause the clock to tick endlessly, no? without ever getting a data.onPush
          // TODO: no!  the clock can't tick until it receives demand from all its sources, including dataInlet
          if (downstreamWaiting) { /*logger.debug(s"onPush[clock]: downstream is waiting");*/ pull(clockInlet) }
          assert(hasBeenPulled(dataInlet))
          /*if (!hasBeenPulled(dataInlet))*/ /*pull(dataInlet)*/ // onPull triggers pull(clockInlet) and then clockInlet's onPush triggers pull(dataInlet)
        }
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish[clock] ${isAvailable(clockInlet)}")
        if (!isAvailable(clockInlet)) completeStage()
        willShutDown = true
      }
    })

    /** Data InHandler */
    setHandler(dataInlet, new InHandler {

      override def onPush(): Unit = {
        val d: Data[T] = grab(dataInlet)
        buffer += d
        logger.debug(s"onPush[data]: $d -> ${buffer.size} -> $watermark")
        if (!pushOneMaybe() && downstreamWaiting) {
          pull(clockInlet)
          pull(dataInlet)
        }
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"onUpstreamFinish[data]")
        if (!isAvailable(dataInlet)) completeStage()
        willShutDown = true
      }
    })

    /** OutHandler (see ScalaDoc on Join's OutHandler) */
    setHandler(outlet, new OutHandler {

      /**
        * "onPull() is called when the output port is ready to emit the next element, push(outlet, elem) is now
        * allowed to be called on this port.
        */
      override def onPull(): Unit = {
        logger.debug(s"onPull")
        downstreamWaiting = true
        if (!pushOneMaybe()) {

          // clockInlet should onPush first b/c dataInlet should be a dependency of it, `data` also needs to signal
          // demand from `clock` before `clock` can onPush and only then can `data` onPush
          /*if (!hasBeenPulled(clockInlet))*/ pull(clockInlet)
          /*if (!hasBeenPulled(dataInlet))*/ pull(dataInlet)
        }

        // 1. downstream signals demand to onPull
        // 2. push&wait: if something can be pushed, it gets pushed and we wait for the next demand signal
        // 3. if nothing can be pushed, we signal demand to both inlets,
        //    must signal to both because dataInlet needs to also signal demand to shared clock which can only push
        //    once it has received a demand signal from all of its downstream consumers
        // 4. clockInlet receives onPush
        // 5. push&wait
        // 6. if nothing can be pushed, we know there's a dataInlet onPush that's on the way
        // 7. `data` source has preloaded future data up to, but not including, next clock tick,
        //    and dataInlet receives onPush
        // 8. push&wait
        // 9. if nothing can be pushed (e.g. if there wasn't any preloaded data) but downstream is still
        //    `downstreamWaiting`, then pull from both inlets again
      }
    })
  }

  override def toString = "Join2"
}
