/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream._
import akka.stream.scaladsl.Sink
import com.google.inject.name.Named
import com.google.inject.{Guice, Provides, Singleton}
import com.hamstoo.daos.{MarkDao, RepresentationDao, UserDao}
import com.hamstoo.models._
import com.hamstoo.models.Representation.{ReprType, Vec, VecEnum}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService}
import com.hamstoo.stream.config.{ConfigModule, FacetsModel, StreamModule}
import com.hamstoo.stream.facet.{AggregateSearchScore, Recency, SearchResults}
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{DataInfo, ExtendedTimeStamp}
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * FacetTests
  */
class FacetTests
  extends AkkaMongoEnvironment("FacetTests-ActorSystem")
    with org.scalatest.mockito.MockitoSugar
    with FutureHandler {

  val logger = Logger(classOf[FacetTests])
  type OutType = FacetsModel.OutType // (String, AnyRef)

  "FacetsModel" should "compute SearchResults" in {

    // filter so that the test doesn't break as more facets are added to FacetsModel
    val facetName = classOf[SearchResults].getSimpleName
    val x = facetsSeq.filter(_._1 == facetName)
      .map { d => logger.info(s"\033[37m$facetName: $d\033[0m"); d }
      .foldLeft(0.0) { case (agg, d) =>
        agg + d._2.asInstanceOf[Datum[SearchResults.typ]].value._3.map(_.sum).getOrElse(0.3)
      }

    x shouldBe (39.55 +- 0.01)
  }

  it should "compute AggregateSearchScore" in {

    val facetName = classOf[AggregateSearchScore].getSimpleName
    val x = facetsSeq.filter(_._1 == facetName)
      .map { d => logger.info(s"\033[37m$facetName: $d\033[0m"); d }
      .foldLeft(0.0) { case (agg, d0) => d0._2 match { case d: Datum[Double] @unchecked => agg + d.value } }

    x / AggregateSearchScore.COEF shouldBe (39.01 +- 0.01)
  }

  it should "compute Recency" in {

    val facetName = classOf[Recency].getSimpleName
    val x = facetsSeq.filter(_._1 == facetName)
      .map { d => logger.info(s"\033[37m$facetName: $d\033[0m"); d }
      .foldLeft(0.0) { case (agg, d0) => d0._2 match { case d: Datum[Double] @unchecked => agg + d.value } }

    // see data-model/docs/RecencyTest.xlsx for an independent calculation of this value
    val coef = (Recency.DEFAULT - 0.5) * 40
    x / coef shouldBe (4.12 +- 0.01)
  }

  // construct the stream graph but don't materialize it, let the individual tests do that
  lazy val facetsSeq: Seq[OutType] = {

    // config values that stream.ConfigModule will bind for DI
    val config = DataInfo.config
    val clockBegin: ClockBegin.typ = new DateTime(2018, 1,  1, 0, 0).getMillis
    val clockEnd  : ClockEnd  .typ = new DateTime(2018, 1, 15, 0, 0).getMillis
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
    (b to e by (e - b) / (nMarks - 1)).zipWithIndex.foreach { case (ts, i) =>
      val vs = Map(VecEnum.PC1.toString -> Seq(ts.dt.getDayOfMonth.toDouble, 3.0, 2.0))
      val r = baseRepr.copy(id = s"r_${ts.Gs}", vectors = vs)
      val ri = ReprInfo(r.id, ReprType.PUBLIC)
      val m = Mark(userId, s"m_${ts.Gs}", MarkData(query, None), reprs = Seq(ri), timeFrom = ts)
      logger.info(s"\033[37m$m\033[0m")
      Await.result(marksDao.insert(m), 8 seconds)
      if (i != nMarks - 1) Await.result(reprsDao.insert(r), 8 seconds) // skip one at the end for a better test of Join
    }

    // this commented out line would have the same effect as below, but in the hamstoo project we already have an
    // appInjector and we need to call createChildInjector from it, so we do so here also to better mimic that scenario
    //val streamInjector = Guice.createInjector(ConfigModule(DataInfo.config), new StreamModule {
    val appInjector = Guice.createInjector(ConfigModule(DataInfo.config))

    // bind some stuff in addition to what's required by StreamModule
    val streamInjector = appInjector.createChildInjector(new StreamModule {

      override def configure(): Unit = {
        super.configure()
        logger.info(s"Configuring module: ${getClass.getName}")

        // TODO: make these things (especially the DAOs) support DI as well so that these extra bindings can be removed
        classOf[ExecutionContext] := system.dispatcher
        classOf[Materializer] := materializer
        classOf[MarkDao] := marksDao
        classOf[RepresentationDao] := reprsDao
        classOf[UserDao] := userDao

        //Val("clock.begin"):~ TimeStamp =~ clockBegin // alternative syntax? more like Scala?
        ClockBegin := clockBegin
        ClockEnd := clockEnd
        ClockInterval := clockInterval
        Query := query
        CallingUserId := userId
        LogLevelOptional := Some(ch.qos.logback.classic.Level.TRACE)

        // fix this value (don't use default DateTime.now) so that computed values don't change every day
        Recency.CurrentTimeOptional() := new DateTime(2018, 4, 19, 0, 0).getMillis

        // we're 100% relying on semantic (vector similarity), marked-content so these inputs quadruple the output value
        AggregateSearchScore.SemanticWeight() := 1.0
        AggregateSearchScore.UserContentWeight() := 0.0

        // finally, bind the model
        classOf[FacetsModel] := classOf[FacetsModel.Default]
      }

      /** Provides a VectorEmbeddingsService for SearchResults to use via StreamModule.provideQueryVec. */
      @Provides @Singleton
      def provideVecSvc(@Named(Query.name) query: Query.typ,
                        idfModel: IDFModel): VectorEmbeddingsService = new VectorEmbeddingsService(null, idfModel) {

        override def countWords(words: Seq[String]): Future[Map[String, (Int, Vec)]] = {
          Future.successful(query.split(" ").map(_ -> (1, baseVec)).toMap)
        }
      }
    })

    logger.info(s"App injector: ${appInjector.hashCode}, stream injector: ${streamInjector.hashCode}")

    import net.codingwell.scalaguice.InjectorExtensions._
    val facetsModel = streamInjector.instance[FacetsModel]

    // this sink is no longer necessary now that filtering is happening after materialization
    //val sink: Sink[OutType, Future[Seq[OutType]]] = Flow[OutType].toMat(Sink.seq)(Keep.right)

    // causes "[error] a.a.OneForOneStrategy - CommandError[code=11600, errmsg=interrupted at shutdown" for some reason
    //facetsModel.run(sink).futureValue

    Await.result(facetsModel.flatRun(Sink.seq), 15 seconds)
  }
}