package com.hamstoo.stream

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream._
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source, ZipWith}
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaEnvironment
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * DataStreamTests
  */
class DataStreamTests
  extends AkkaEnvironment("DataStreamTests-ActorSystem")
    with FutureHandler {

  val logger = Logger(classOf[DataStreamTests])

  /*"TimeWindowReducer" should "process URLs" in {

    case class IntId(id: Int) extends EntityId

    val dt = new DateTime(2018, 2, 7)

    val src = Source((0 until 10).map(i => Datum(IntId(i), dt.plusMinutes(i).getMillis, i)))
    val ds = DataStreamRef(src)

    //TimeDecay(ds, 1 minute)

    // TODO: Source.tick..... the clock pulls the data
    // "There are tools in Akka Streams however that enable the rates of different segments of a processing chain
    // to be “detached” or to define the maximum throughput of the stream through external timing sources."
    //   https://doc.akka.io/docs/akka/2.5.3/scala/stream/stream-rate.html


    // streams should throttle themselves according to the source clock
    // send snapshots (or batches of datums) at tick times
    // so streams can decide themselves when to update their internal caches

    // really we need producers to emit elements only when the clock ticks, so perhaps the clock is a source
    // that broadcasts rather than a sink that pulls -- yes, and the ticking triggers the source to load more
    // elements

    // so the clock should be a sink, CurrentTimeSink, that "emits" the current time (yes, you read that right, a
    // "sink that emits") or maybe rather a singleton that holds the current time, and every DataStream reads this
    // value?  by hooking up to it as a stream?  probably better to not request the current time value that way.
    // is there a way for the CurrentTimeSink to propagate this time back when it pulls from upstream?

    // then, eventually, this clock can be turned into a flow--or a sequence of sinks--that loops over time pulling
    // new data for each time point

    // *********
    // the clock should be an implicit input of all DataStreams?!?!?!!?!?
    // *********

    // OR.... use BidiFlows and have clock time flow down the tree into requests for data.... and have responses
    // to those requests flow up the tree
    // YES - have "requests" flow down, e.g. current time along with requested frequencies?

    // OR
    // 1) assume data is stored in the database with time knowns (as well as source times, or we can compute time knowns)
    // 2) the source clock gets mapped to data sources, which trigger them to load their data
    // 3) the data sources somehow know to whom they've been hooked so they can know how much (and at what frequency)
    //    of their data to retain in an inner cache (BlockManager)
    //      No - data sources would know too much about their dependants
    // 4) the data sources just flow their data into their cache as quickly as timeknown allows
    // 5) dependents "pull" from the cache
    // NO NO NO.... this is the same as the BidiFlow approach... just that dependencies get established as
    // timeknown flows upstream and then they are satisfied as data flows downstream
    //    in this way, it looks like there is a clock on the source end
    // But a sink is the only way to trigger a flow and that's what we want... a sink to trigger!!!!

  }*/




  /*what happens when you zip a stream of natural numbers with even numbers?
  the process of zipping is what we want to abstractify away first (functional style)
  then create another abstraction to combine binary ops into zip ops for the dsl
  perhaps using reflection

  this is the same mechanism that should report data requirement lags down the dependency chain*/



  /*"Test" should "scratch test 0" in {
    // https://doc.akka.io/docs/akka/2.5.3/scala/stream/stream-rate.html#internal-buffers-and-their-effect

    implicit val materializer: Materializer = ActorMaterializer()

    case class Tick() {
      println(s"Tick print ${DateTime.now}")
    }

    // this sink can be defined inside also, right where it's to'ed (~>'ed), and `b => sink =>` would just become
    // `b =>`, in that case however, g.run() doesn't return a Future and so can't be Await'ed, either way though,
    // the Await crashes
    val resultSink = Sink.foreach((x: Int) => println(s"Sink print ${DateTime.now}: $x"))

    // "lower-level GraphDSL API" https://softwaremill.com/reactive-streams-in-scala-comparing-akka-streams-and-monix-part-3/
    val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit builder => sink =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val zw = ZipWith[Tick, Int, Int]((_tick, count) => count).async
      //.addAttributes(Attributes.inputBuffer(initial = 1, max = 1)) // this works!

      // this is the asynchronous stage in this graph (see the `.async`)
      val zipper: FanInShape2[Tick, Int, Int] = builder.add(zw)

      // first input port gets hooked up to src0
      val src0 = Source.tick(initialDelay = 3.second, interval = 3.second, Tick())
      src0 ~> zipper.in0

      // second input port gets hooked up to src1
      // the following 2 types are equivalent Source[String, Cancellable]#Repr[Int] and Source[Int, Cancellable]
      val src1: Source[Int, Cancellable] = Source.tick(initialDelay = 1.second, interval = 1.second, "message!")

        // "the conflateWithSeed step here is configured so that it counts the number of elements received before
        // the downstream ZipWith consumes them."  it takes 2 arguments:
        // 1) "A seed function that produces the zero element for the folding process that happens when the upstream
        //    is faster than the downstream."
        // 2) "A fold function that is invoked when multiple upstream messages needs to be collapsed to an aggregate
        //     value due to the insufficient processing rate of the downstream."
        // it doesn't make any sense, in this example, to conflate src0 because no src0 ticks are being missed, it's
        // the source that's limiting the rate of the downstream ZipWith
        .conflateWithSeed(seed = (_) => 1)(aggregate = (count, _messageTick) => count + 1)

      src1 ~> zipper.in1

      // the single output port gets hooked up to the sink/consumer
      zipper.out ~> sink
      ClosedShape
    })

    // addAttributes: "Running the above example one would expect the number 3 to be printed in every 3 seconds.  What
    // is being printed is different though, we will see the number 1. The reason for this is the internal buffer which
    // is by default 16 elements large, and prefetches elements before the ZipWith starts consuming them"
    //g.addAttributes(Attributes.inputBuffer(initial = 1, max = 1)).run() // this works!
    g.run()
    //Await.result(g.run(), 15 seconds)
    Thread.sleep(65.seconds.toMillis)
  }*/


  "Test" should "scratch test 1" in {
    implicit val materializer: Materializer = ActorMaterializer()

    // this sink can be defined inside also, right where it's to'ed (~>'ed), and `b => sink =>` would just become
    // `b =>`, in that case however, g.run() doesn't return a Future and so can't be Await'ed, either way though,
    // the Await crashes
    val resultSink = Sink.foreach((x: Datum[Int]) => logger.info(s"Sink print ${DateTime.now}: $x"))

    // "lower-level GraphDSL API" https://softwaremill.com/reactive-streams-in-scala-comparing-akka-streams-and-monix-part-3/
    val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit builder => sink =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val jw = Join[Int, Int, Int]((a, b) => a * 100 + b).async

      // this is the asynchronous stage in this graph (see the `.async`)
      val joiner/*: FanInShape2[Int, Int, String]*/ = builder.add(jw)

      val src0 = Source((0 until 10     ).map(i => Datum(ReprId(s"id$i"), i, i, i)))
      val src1 = Source((0 until 10 by 3).map(i => Datum(UnitId()       , i, i, i)))

      src0 ~> joiner.in0
      src1 ~> joiner.in1

      joiner.out ~> sink
      ClosedShape
    })

    g.run()
    Thread.sleep(20.seconds.toMillis)
    //Await.result(g.run(), 15 seconds)
  }
}