package com.hamstoo.stream

import akka.{Done, NotUsed}
import akka.actor.Cancellable
import akka.stream._
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, ZipWith}
import com.google.inject.Guice
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoVectorsDao}
import com.hamstoo.models.Representation.Vec
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{DataInfo, DurationMils, ExtendedTimeStamp, TimeStamp}
import com.typesafe.config.ConfigValueFactory
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSClient
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * DataStreamTests
  */
class DataStreamTests
  extends AkkaMongoEnvironment("DataStreamTests-ActorSystem")
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

    val source: Source[Data[Int], NotUsed] = Source.fromGraph(GraphDSL.create() { implicit builder =>
      import akka.stream.scaladsl.GraphDSL.Implicits._

      val jw = Join[Int, Int, Int]((a, b) => a * 100 + b).async
      val joiner = builder.add(jw)

      val src0 = Source((0 until 10     ).map(i => Datum(ReprId(s"id$i"), i, i)))
      val src1 = Source((0 until 10 by 4).map(i => Datum(UnitId()       , i, i)))

      src0 ~> joiner.in0
      src1 ~> joiner.in1

      SourceShape(joiner.out)
    })

    val foldSink = Sink.fold[Int, Int](0) { (a, b) =>
      logger.info(s"Log sink: $a + $b")
      a + b
    }
    val runnable: RunnableGraph[Future[Int]] = source.map(_.oval.get.value).toMat(foldSink)(Keep.right)
    val x: Int = Await.result(runnable.run(), 15 seconds)

    logger.info(s"****** Join should join DataStreams: x = $x")
    x shouldEqual 1212
  }

  "GroupReduce" should "cross-sectionally reduce streams of (singular) Datum" in {

    val iter = List(Datum(ReprId("a"), 0, 0.4),
                    Datum(ReprId("b"), 0, 0.6),
                    Datum(ReprId("b"), 1, 1.2),
        Datum(ReprId("d"), 0, 300.0), // timestamp out of order!
                    Datum(ReprId("c"), 1, 1.5),
                    Datum(ReprId("a"), 1, 1.8),
                    Datum(ReprId("c"), 2, 2.5),
        Datum(ReprId("b"), 3, 3.4), // entity IDs can be duplicated per timestamp w/ singular Datum
        Datum(ReprId("b"), 3, 3.6))

    testGroupReduce(iter, "(singular) Datum")
  }

  "GroupReduce" should "cross-sectionally reduce streams of (plural) Data" in {

    def SV(v: Double, ts: TimeStamp) = SourceValue(v, ts)

    val iter = List(Data(0, Map(ReprId("a") -> SV(0.4, 0), ReprId("b") -> SV(0.6, 0))),
                    Data(1, Map(ReprId("b") -> SV(1.2, 1), ReprId("c") -> SV(1.5, 1), ReprId("a") -> SV(1.8, 1))),
        Datum(ReprId("d"), 0, 300.0), // timestamp out of order!
                    Datum(ReprId("c"), 2, 2.5),
        Data(3, Map(ReprId("b") -> SV(3.5, 3), ReprId("b") -> SV(3.5, 3)))) // duplicate entity IDs get merged in a Map

    testGroupReduce(iter, "(plural) Data")
  }

  /** Used by both of the 2 tests above. */
  def testGroupReduce(iter: immutable.Iterable[Data[Double]], what: String) {

    val grouper = () => new CrossSectionCommandFactory()

    val source = GroupReduce(Source(iter), grouper) { (ds: Vec) =>
      import com.hamstoo.models.Representation.VecFunctions
      ds.mean
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
      val bcast = builder.add(Broadcast[Data[Double]](2))

      val toValue = Flow[Data[Double]].map(_.oval.get.value)
      val logSink = Sink.foreach((x: Data[Double]) => logger.info(s"Log sink: $x"))

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

  "Facet values" should "be generated" in {

    def confval(v: AnyRef) = ConfigValueFactory.fromAnyRef(v)

    val config = DataInfo.config
      .withValue("query", confval("some query"))
      .withValue("user.id", confval(DataInfo.constructUserId().toString))

    val injector = Guice.createInjector(new StreamModule(config) {
      override def configure(): Unit = {
        super.configure()
        bind[WSClient].toInstance(AhcWSClient())
        bind[MongoVectorsDao].toInstance(vectorsDao)
        bind[ExecutionContext].toInstance(system.dispatcher)
        bind[Materializer].toInstance(materializer)

        // TODO: these dates should be determined via the injector
        val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0).getMillis
        val stop: TimeStamp = new DateTime(2018, 2, 1, 0, 0).getMillis
        val interval: DurationMils = (1 day).toMillis
        val clock = Clock(start, stop, interval)
        bind[Clock]/*.annotatedWith(Names.named("clock"))*/.toInstance(clock)

        bind[MongoMarksDao].toInstance(marksDao)
        bind[MongoRepresentationDao].toInstance(reprsDao)

      }
    })

    import net.codingwell.scalaguice.InjectorExtensions._
    val streamModel: StreamModel = injector.instance[StreamModel]

    val headStream: DataStream[Double] = streamModel.facets.head._2
    val fut: Future[Done] = headStream.source.runWith(Sink.foreach[Data[Double]](d => println(s"$d")))

    // look, can even start this after runWith! (but still haven't determined if it's actually necessary yet)
    injector.instance[Clock].start()

    Await.result(fut, 15 seconds)
  }

  "Clock" should "throttle a DataStream" in {


   /* val producer = Source.tick(1.second, 1.second, "New message")

    // Attach a BroadcastHub Sink to the producer. This will materialize to a
    // corresponding Source.
    // (We need to use toMat and Keep.right since by default the materialized
    // value to the left is used)
    val runnableGraph: RunnableGraph[Source[String, NotUsed]] =
    producer.toMat(BroadcastHub.sink(bufferSize = 256))(Keep.right)

    // By running/materializing the producer, we get back a Source, which
    // gives us access to the elements published by the producer.
    val fromProducer: Source[String, NotUsed] = runnableGraph.run()

    // Print out messages from the producer in two independent consumers
    fromProducer.runForeach(msg ⇒ println("consumer1: " + msg))
    fromProducer.runForeach(msg ⇒ println("consumer2: " + msg))*/

    implicit val ec: ExecutionContext = system.dispatcher

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    //clock.start() // TODO: get rid of this?  or try BroadcastHub again

    // TODO: ask about this on stackoverflow
    // this doesn't print anything
    /*val source = Source(0 to 20)
      //.addAttributes(Attributes.inputBuffer(initial = 1, max = 1))
      .buffer(1, OverflowStrategy.backpressure)
      .runWith(BroadcastHub.sink[Int])
    Thread.sleep(3000)
    source.runForeach(n => println(s"------------- source1: $n"))
    Thread.sleep(3000)
    source.runForeach(n => println(s"------------- source2: $n"))*/

    // changing this interval should not affect the results (even if it's fractional)
    val preloadInterval = 3 days

    class TestSource extends DataSource[TimeStamp](preloadInterval.toMillis) {
      //override val logger = Logger(classOf[TestSource]) // this line causes a NPE for some reason
      override def preload(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[TimeStamp]]] = Future {

        // must snapBegin, o/w DataSource's preloadInterval can affect the results, which it should not
        val dataInterval = (12 hours).toMillis
        val snapBegin = ((begin - 1) / dataInterval + 1) * dataInterval

        val x = (snapBegin until end by dataInterval).map { t =>
          logger.debug(s"preload i: ${t.tfmt}")
          Datum[TimeStamp](MarkId("je"), t, t)
        }
        logger.debug(s"preload end: n=${x.length}")
        x
      }
    }

    val testSource = new TestSource()

    val fut: Future[Int] = testSource.source
      .map { e => logger.debug(s"TestSource: ${e.knownTime.tfmt} / ${e.oval.get.sourceTime.tfmt} / ${e.oval.get.value.dt.getDayOfMonth}"); e }
      .runWith(
        Sink.fold[Int, Data[TimeStamp]](0) { case (agg, d) =>
          agg + d.oval.get.value.dt.getDayOfMonth
        }
      )

    Thread.sleep(3000)
    clock.start()

    val x = Await.result(fut, 15 seconds)
    x shouldEqual 173
  }
}