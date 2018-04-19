/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink}
import com.google.inject.name.Named
import com.google.inject.{Guice, Provides, Singleton}
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
import com.hamstoo.models._
import com.hamstoo.models.Representation.{ReprType, Vec, VecEnum}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService}
import com.hamstoo.stream.facet.SearchResults
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{ConfigModule, DataInfo, ExtendedTimeStamp}
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * DataStreamTests
  */
class FacetTests
  extends AkkaMongoEnvironment("FacetTests-ActorSystem")
    with org.scalatest.mockito.MockitoSugar
    with FutureHandler {

  val logger = Logger(classOf[FacetTests])

  "Facet values" should "be generated" in {

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
      val m = Mark(userId, s"m_${ts.Gs}", MarkData("", None), reprs = Seq(ri), timeFrom = ts)
      logger.info(s"\033[37m$m\033[0m")
      Await.result(marksDao.insert(m), 8 seconds)
      if (i != nMarks - 1) Await.result(reprsDao.insert(r), 8 seconds) // skip one at the end for a better test of Join
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

        //Val("clock.begin"):~ TimeStamp =~ clockBegin // alternative syntax? more like Scala?
        ClockBegin := clockBegin
        ClockEnd := clockEnd
        ClockInterval := clockInterval
        Query := query
        CallingUserId := userId
        LogLevelOptional := Some(ch.qos.logback.classic.Level.TRACE)

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

    import net.codingwell.scalaguice.InjectorExtensions._
    val streamModel: FacetsModel = streamInjector.instance[FacetsModel]

    type InType = (String, AnyRef)
    type OutType = Double

    val sink: Sink[InType, Future[OutType]] = Flow[InType]
      .map { d => logger.info(s"\033[37m$d\033[0m"); d }
      .filter(_._1 == "SearchResults") // filter so that the test doesn't break as more facets are added to FacetsModel
      .toMat(Sink.fold[OutType, InType](0.0) { case (agg, d) =>
      agg + d._2.asInstanceOf[Datum[SearchResults.typ]].value._3.map(_.sum).getOrElse(0.3)
    })(Keep.right)

    // causes "[error] a.a.OneForOneStrategy - CommandError[code=11600, errmsg=interrupted at shutdown" for some reason
    //val x = streamModel.run(sink).futureValue

    val x = Await.result(streamModel.run(sink), 15 seconds)

    // (2.63 + 3.1 + 2.1 + 1.91 + 1.77) * 1.4 =~ 16.13
    // (2.63 + 3.1 + 2.1 + 1.91       ) * 1.4 =~ 13.65 (with `if (i != nMarks - 1)` enabled above)
    x shouldBe (13.65 +- 0.01)
  }
}