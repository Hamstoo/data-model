package com.hamstoo.stream

import java.util.UUID

import akka.{Done, NotUsed}
import akka.actor.Cancellable
import akka.event.Logging
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, ZipWith}
import com.google.inject.{Guice, Provides}
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoVectorsDao}
import com.hamstoo.models.{Mark, MarkData, ReprInfo, Representation}
import com.hamstoo.models.Representation.{ReprType, Vec, VecEnum}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService}
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

    // config values that stream.ConfigModule will bind for DI
    def confval[T](v: T) = ConfigValueFactory.fromAnyRef(v)
    val config = DataInfo.config
      .withValue("clock.begin", confval(new DateTime(2018, 1,  1, 0, 0).getMillis))
      .withValue("clock.end",   confval(new DateTime(2018, 1, 15, 0, 0).getMillis))
      .withValue("clock.interval", confval((1 day).toMillis))
      .withValue("query", confval("some query"))
      .withValue("user.id", confval(DataInfo.constructUserId().toString))

    // insert some marks with reprs into the database
    val userId = UUID.fromString(config.getString("user.id"))
    val baseVec = Seq(1.0, 2.0, 3.0)
    val baseVs = Map(VecEnum.PC1.toString -> baseVec)
    val baseRepr = Representation("", None, None, None, "", None, None, None, baseVs, None)
    val b :: e :: Nil = Seq("clock.begin", "clock.end").map(config.getLong)
    (b to e by (e - b) / 4).foreach { ts =>
      val vs = Map(VecEnum.PC1.toString -> Seq(ts.dt.getDayOfMonth.toDouble, 3.0, 2.0))
      val r = baseRepr.copy(id = s"r_${ts.Gs}", vectors = vs)
      val ri = ReprInfo(r.id, ReprType.PUBLIC)
      val m = Mark(userId, s"m_${ts.Gs}", MarkData("", None), reprs = Seq(ri), timeFrom = ts)
      logger.info(s"------------------ $m")
      Await.result(marksDao.insert(m), 5 seconds)
      Await.result(reprsDao.insert(r), 5 seconds)
    }

    // bind some stuff in addition to what's required by StreamModule
    // TODO: make these things support DI as well so that these extra bindings can be removed
    val injector = Guice.createInjector(new StreamModule(config) {

      /** Override configure in a way that we would only do for this test. */
      override def configure(): Unit = {
        super.configure()
        bind[WSClient].toInstance(AhcWSClient())
        bind[MongoVectorsDao].toInstance(vectorsDao)
        bind[ExecutionContext].toInstance(system.dispatcher)
        bind[Materializer].toInstance(materializer)
        bind[MongoMarksDao].toInstance(marksDao)
        bind[MongoRepresentationDao].toInstance(reprsDao)
      }

      /** Provide a VectorEmbeddingsService for QueryCorrelation to use via StreamModule.provideQueryVec. */
      @Provides
      def provideVecSvc(idfModel: IDFModel): VectorEmbeddingsService = new VectorEmbeddingsService(null, idfModel) {
        // StreamModule.provideQueryVec doesn't care if "asdf" isn't a query word
        override def countWords(words: Seq[String]): Future[Map[String, (Int, Vec)]] =
          Future.successful(Map("asdf" -> (1, baseVec)))
      }
    })

    import net.codingwell.scalaguice.InjectorExtensions._
    val streamModel: FacetsModel = injector.instance[FacetsModel]

    val sink: Sink[Data[Double], Future[Double]] = Flow[Data[Double]]
      .map { d => logger.info(s"------------------ $d"); d }
      // so the test doesn't break as more facets are added to FacetsModel
      .filter(_.values.keys.exists(_.asInstanceOf[CompoundId].ids.contains(FacetName("QueryCorrelation"))))
      .toMat(Sink.fold[Double, Data[Double]](0.0) { case (agg, d) => agg + d.oval.get.value })(Keep.right)

    // causes "[error] a.a.OneForOneStrategy - CommandError[code=11600, errmsg=interrupted at shutdown" for some reason
    //val x = streamModel.run(sink).futureValue

    val x = Await.result(streamModel.run(sink), 15 seconds)
    x shouldBe (2.86 +- 0.01)
  }

  "Clock" should "throttle a DataStream" in {

    // https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely
    // this doesn't print anything when lower than 255 (due to bufferSize!)
    /*val source = Source(0 to 255)
      .map { n => if (n % 10 == 0) println(s"*** tick $n"); n }
      .runWith(BroadcastHub.sink[Int](bufferSize = 256))
    source.runForeach { n => Thread.sleep(1); if (n % 10 == 0) println(s"------------- source1: $n") }
    source.runForeach(n => if (n % 10 == 0) println(s"------------- source2: $n"))*/

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    // changing this interval should not affect the results (even if it's fractional)
    val preloadInterval = 3 days

    implicit val ec: ExecutionContext = system.dispatcher
    case class TestSource() extends DataSource[TimeStamp](preloadInterval.toMillis) {
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

    val fut: Future[Int] = TestSource().source
      .map { e => logger.debug(s"TestSource: ${e.knownTime.tfmt} / ${e.oval.get.sourceTime.tfmt} / ${e.oval.get.value.dt.getDayOfMonth}"); e }
      .runWith(
        Sink.fold[Int, Data[TimeStamp]](0) { case (agg, d) => agg + d.oval.get.value.dt.getDayOfMonth }
      )

    clock.start()

    val x = Await.result(fut, 15 seconds)
    x shouldEqual 173
  }
}