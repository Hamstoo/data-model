/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.{Done, NotUsed}
import akka.actor.Cancellable
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, ZipWith}
import com.google.inject.name.Named
import com.google.inject.{Guice, Provides}
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
import com.hamstoo.models._
import com.hamstoo.models.Representation.{ReprType, Vec, VecEnum}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService}
import com.hamstoo.stream.Join.JoinWithable
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{ConfigModule, DataInfo, DurationMils, ExtendedTimeStamp, TimeStamp}
import org.joda.time.{DateTime, DateTimeZone}
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

  /*"Test" should "scratch test 0" in {
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

    val src0 = Source((0 until 10     ).map(i => Datum(ReprId(s"id$i"), i, i))).named("TestJoin0")
    val src1 = Source((0 until 10 by 4).map(i => Datum(UnitId()       , i, i))).named("TestJoin1")

    // see comment on JoinWithable as to why the cast is necessary here
    val source: Source[Data[Int], NotUsed] =
      src0.joinWith(src1)((a, b) => a * 100 + b).asInstanceOf[src0.Repr[Data[Int]]]

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
  }*/

  "Facet values" should "be generated" in {

    // config values that stream.ConfigModule will bind for DI
    val config = DataInfo.config
    val clockBegin: ClockBegin.typ = new DateTime(2018, 1,  1, 0, 0).getMillis
    val clockEnd: ClockEnd.typ = new DateTime(2018, 1, 15, 0, 0).getMillis
    val clockInterval: ClockInterval.typ = (1 day).toMillis
    val query: Query.typ = "some query"
    val userId: CallingUserId.typ = DataInfo.constructUserId()

    // insert 5 marks with reprs into the database
    val nMarks = 5
    val baseVec = Seq(1.0, 2.0, 3.0)
    val baseVs = Map(VecEnum.PC1.toString -> baseVec)
    val baseRepr = Representation("", None, None, None, "", None, None, None, baseVs, None)
    //val b :: e :: Nil = Seq(ClockBegin.name, ClockEnd.name).map(config.getLong)
    val (b, e) = (clockBegin, clockEnd)
    (b to e by (e - b) / (nMarks - 1)).foreach { ts =>
      val vs = Map(VecEnum.PC1.toString -> Seq(ts.dt.getDayOfMonth.toDouble, 3.0, 2.0))
      val r = baseRepr.copy(id = s"r_${ts.Gs}", vectors = vs)
      val ri = ReprInfo(r.id, ReprType.PUBLIC)
      val m = Mark(userId, s"m_${ts.Gs}", MarkData("", None), reprs = Seq(ri), timeFrom = ts)
      logger.info(s"\033[37m$m\033[0m")
      Await.result(marksDao.insert(m), 8 seconds)
      Await.result(reprsDao.insert(r), 8 seconds)
    }

    // bind some stuff in addition to what's required by StreamModule
    val streamInjector = Guice.createInjector(ConfigModule(DataInfo.config), new StreamModule {

      override def configure(): Unit = {
        super.configure()
        logger.info(s"Configuring module: ${getClass.getName}")

        // TODO: make these things (especially the DAOs) support DI as well so that these extra bindings can be removed
        classOf[ExecutionContext] := system.dispatcher
        classOf[Materializer] := materializer
        classOf[MongoMarksDao] := marksDao
        classOf[MongoRepresentationDao] := reprsDao
        classOf[MongoUserDao] := userDao

        ClockBegin := clockBegin
        ClockEnd := clockEnd
        ClockInterval := clockInterval
        Query := query
        CallingUserId := userId
        LogLevelOptional := Some(ch.qos.logback.classic.Level.TRACE)
      }

      /** Provides a VectorEmbeddingsService for SearchResults to use via StreamModule.provideQueryVec. */
      @Provides
      def provideVecSvc(@Named(Query.name) query: Query.typ,
                        idfModel: IDFModel): VectorEmbeddingsService = new VectorEmbeddingsService(null, idfModel) {

        override def countWords(words: Seq[String]): Future[Map[String, (Int, Vec)]] = {
          Future.successful(query.split(" ").map(_ -> (1, baseVec)).toMap)
        }
      }
    })

    import net.codingwell.scalaguice.InjectorExtensions._
    val streamModel: FacetsModel = streamInjector.instance[FacetsModel]

    type InType = (String, AnyRef)
    type OutType = Double

    val sink: Sink[InType, Future[OutType]] = Flow[InType]
      .map { d => logger.info(s"\033[37m$d\033[0m"); d }
      .filter(_._1 == "SearchResults") // filter so that the test doesn't break as more facets are added to FacetsModel
      .toMat(Sink.fold[OutType, InType](0.0) { case (agg, d) =>
        agg + d._2.asInstanceOf[Data[(MSearchable, String, Option[Double])]].oval.get.value._3.getOrElse(0.3)
      })(Keep.right)

    // causes "[error] a.a.OneForOneStrategy - CommandError[code=11600, errmsg=interrupted at shutdown" for some reason
    //val x = streamModel.run(sink).futureValue

    val x = Await.result(streamModel.run(sink), 15 seconds)
    x shouldBe (16.13 +- 0.01)
  }

  /*"Clock" should "throttle a PreloadSource" in {

    // https://stackoverflow.com/questions/49307645/akka-stream-broadcasthub-being-consumed-prematurely
    // this doesn't print anything when lower than 255 (due to bufferSize!)
    /*val source = Source(0 to 255)
      .map { n => if (n % 10 == 0) println(s"*** tick $n"); n }
      .runWith(BroadcastHub.sink[Int](bufferSize = 256))
    source.runForeach { n => Thread.sleep(1); if (n % 10 == 0) println(s"------------- source1: $n") }
    source.runForeach(n => if (n % 10 == 0) println(s"\033[37msource2\033[0m: $n"))*/

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    // changing this interval should not affect the results (even if it's fractional)
    val preloadInterval = 3 days

    implicit val ec: ExecutionContext = system.dispatcher
    case class TestSource() extends PreloadSource[TimeStamp](preloadInterval.toMillis) {
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

    val x = Await.result(fut, 10 seconds)
    x shouldEqual 173
  }

  "Clock" should "throttle a ThrottledSource" in {

    // changing these (including interval) will affect results b/c it changes the effective time range the clock covers
    val start: TimeStamp = new DateTime(2018, 1, 1, 0, 0, DateTimeZone.UTC).getMillis
    val stop: TimeStamp = new DateTime(2018, 1, 10, 0, 0, DateTimeZone.UTC).getMillis
    val interval: DurationMils = (2 days).toMillis
    implicit val clock: Clock = Clock(start, stop, interval)

    implicit val ec: ExecutionContext = system.dispatcher
    case class TestSource() extends ThrottledSource[TimeStamp]() {

      // this doesn't work with a `val` (NPE) for some reason, but it works with `lazy val` or `def`
      override lazy val throttlee: SourceType = Source {

        // no need to snapBegin like with PreloadSource test b/c peeking outside of the class for when to start
        val dataInterval = (12 hours).toMillis

        // a preload source would backup the data until the (exclusive) beginning of the tick interval before `start`,
        // so that's what we do here with `start - interval + dataInterval` to make the two test results match up
        (start - interval + dataInterval until stop by dataInterval).map { t =>
          Datum[TimeStamp](MarkId("kf"), t, t)
        }
      }.named("TestThrottledSource")
    }

    val fut: Future[Int] = TestSource().source
      .map { e => logger.debug(s"TestSource: ${e.knownTime.tfmt} / ${e.oval.get.sourceTime.tfmt} / ${e.oval.get.value.dt.getDayOfMonth}"); e }
      .runWith(
        Sink.fold[Int, Data[TimeStamp]](0) { case (agg, d) => agg + d.oval.get.value.dt.getDayOfMonth }
      )

    Thread.sleep(5000)
    clock.start()

    val x = Await.result(fut, 10 seconds)
    x shouldEqual 173
  }*/
}