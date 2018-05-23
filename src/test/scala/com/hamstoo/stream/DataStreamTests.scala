/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.{Done, NotUsed}
import akka.actor.Cancellable
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, ZipWith}
import com.hamstoo.stream.Data.Data
import com.hamstoo.stream.Join.JoinWithable
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import org.joda.time.DateTime
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * DataStreamTests
  */
class DataStreamTests
  extends AkkaMongoEnvironment("DataStreamTests-ActorSystem")
    with org.scalatest.mockito.MockitoSugar
    with FutureHandler {

  val logger = Logger(classOf[DataStreamTests])

  "Test" should "scratch test 0" in {
    // https://doc.akka.io/docs/akka/2.5.3/scala/stream/stream-rate.html#internal-buffers-and-their-effect

    implicit val materializer: Materializer = ActorMaterializer()

    case class Tick() { logger.info(s"Tick print ${DateTime.now}") }

    // this sink can be defined inside also, right where it's to'ed (~>'ed), and `b => sink =>` would just become
    // `b =>`, in that case however, g.run() doesn't return a Future and so can't be Await'ed, either way though,
    // the Await crashes
    // "Another alternative is to pass existing graphs—of any shape—into the factory method that produces a new
    // graph. The difference between these approaches is that importing using builder.add(...) ignores the
    // materialized value of the imported graph while importing via the factory method allows its inclusion"
    val resultSink = Sink.foreach((x: Int) => logger.info(s"Log sink: $x"))

    // "lower-level GraphDSL API" https://softwaremill.com/reactive-streams-in-scala-comparing-akka-streams-and-monix-part-3/
    val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit builder: GraphDSL.Builder[Future[Done]] => sink =>
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
    Thread.sleep(20.seconds.toMillis)
  }

  "Join" should "join DataStreams" in {
    implicit val materializer: Materializer = ActorMaterializer()

    // use the same knownTime for all data to ensure that (1) data is joined by sourceTime and (2) Join doesn't
    // terminate early before joining all available data
    val knownTime = 11

    // let half of the data expire (sourceTime far behind knownTime), but we also need to use this value to ensure
    // that we start the sources far enough in time so that Join's watermarks are updated at each step
    val expireAfter = knownTime / 2
    val end = expireAfter + 10
    assert(end - expireAfter < knownTime)

    // this test should work with either `knownTime` or `i` as last (knownTime) parameter of Datum.apply
    def newSource(step: Int, id: EntityId, name: String): Source[Data[Int], NotUsed] =
      Source((expireAfter until end by step).map(i => Data(Datum(i - expireAfter, id, i, knownTime)))).named(name)
    val src0 = newSource(1, ReprId(s"id"), "TestJoin0")
    val src1 = newSource(4, UnitId       , "TestJoin1")

    // see comment on JoinWithable as to why the cast is necessary here
    val source: Source[Datum[Int], NotUsed] =
      src0.joinWith(src1)((a, b) => a * 100 + b, expireAfter = expireAfter)
        .asInstanceOf[src0.Repr[Data[Int]]]
        .mapConcat(identity)

    val foldSink = Sink.fold[Int, Int](0) { (a, b) =>
      logger.info(s"Log sink: $a + $b")
      a + b
    }
    val runnable: RunnableGraph[Future[Int]] = source.map(_.value).toMat(foldSink)(Keep.right)
    val x: Int = Await.result(runnable.run(), 15 seconds)

    logger.info(s"****** Join should join DataStreams: x = $x")
    x shouldEqual 1212
  }

  "GroupReduce" should "cross-sectionally reduce streams of (singular) Datum" in {

    val iter = List(Datum(0.4, ReprId("a"), 0),
      Datum(0.6, ReprId("b"), 0),
      Datum(1.2, ReprId("b"), 1),
      Datum(300.0, ReprId("d"), 0), // timestamp out of order!
      Datum(1.5, ReprId("c"), 1),
      Datum(1.8, ReprId("a"), 1),
      Datum(2.5, ReprId("c"), 2),
      Datum(3.4, ReprId("b"), 3), // entity IDs can be duplicated per timestamp w/ singular Datum
      Datum(3.6, ReprId("b"), 3))

    testGroupReduce(iter, "(singular) Datum")
  }

  it should "cross-sectionally reduce streams of (plural) Data (which we actually no longer have)" in {

    val iter = List(Datum(0.4, ReprId("a"), 0), Datum(0.6, ReprId("b"), 0),
      Datum(1.2, ReprId("b"), 1), Datum(1.5, ReprId("c"), 1), Datum(1.8, ReprId("a"), 1),
      Datum(300.0, ReprId("d"), 0), // timestamp out of order!
      Datum(2.5, ReprId("c"), 2),
      Datum(3.5, ReprId("b"), 3), Datum(3.5, ReprId("b"), 3)) // duplicate entity IDs get merged in a Map

    testGroupReduce(iter, "(plural) Data")
  }

  /** Used by both of the 2 tests above. */
  def testGroupReduce(iter: immutable.Iterable[Datum[Double]], what: String) {

    val grouper = () => new CrossSectionCommandFactory()

    val source = GroupReduce(Source(iter), grouper) { (ds: Traversable[Double]) =>
      import com.hamstoo.models.Representation.VecFunctions
      ds.toSeq.mean
    }

    // "providing a sink as argument turns the Graph Mat type from NotUsed to Future[Done]"
    // https://stackoverflow.com/questions/35516519/how-to-test-an-akka-stream-closed-shape-runnable-graph-with-an-encapsulated-sour
    val foldSink = Sink.fold[Double, Double](0)(_ + _)

    // "Another alternative is to pass existing graphs—of any shape—into the factory method that produces a new
    // graph. The difference between these approaches is that importing using builder.add(...) ignores the
    // materialized value of the imported graph while importing via the factory method allows its inclusion"
    val g: RunnableGraph[Future[Double]] = RunnableGraph.fromGraph(GraphDSL.create(foldSink) { implicit builder => foldSink =>
      import GraphDSL.Implicits._

      // "[import] merge and broadcast junctions...into the graph using builder.add(...), an operation that will
      // make a copy of the blueprint that is passed to it and return the inlets and outlets of the resulting copy
      // so that they can be wired up"
      val bcast = builder.add(Broadcast[Datum[Double]](2))

      val toValue = Flow[Datum[Double]].map(_.value)
      val logSink = Sink.foreach((x: Datum[Double]) => logger.info(s"Log sink: $x"))

      // 2 different ways to write this
      //source ~> bcast.in
      //          bcast.out(0) ~> logSink
      //          bcast.out(1) ~> toValue ~> foldSink
      source ~> bcast ~> logSink
                bcast ~> toValue ~> foldSink

      ClosedShape
    })

    val x: Double = Await.result(g.run(), 15 seconds)
    logger.info(s"****** GroupReduce should cross-sectionally reduce streams of $what: x = $x")
    x shouldEqual 8.0
  }
}