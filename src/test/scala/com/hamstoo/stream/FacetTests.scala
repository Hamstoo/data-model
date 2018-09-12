/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream

import java.util.UUID

import akka.stream._
import akka.stream.scaladsl.Sink
import com.google.inject.{Inject, Injector, Provides, Singleton}
import com.hamstoo.models.Mark.{MarkAux, RangeMils}
import com.hamstoo.models._
import com.hamstoo.models.Representation.{ReprType, UserVecEnum, Vec, VecEnum}
import com.hamstoo.services.{IDFModel, VectorEmbeddingsService}
import com.hamstoo.stream.config.{FacetsModel, StreamModule}
import com.hamstoo.stream.dataset.MarksStream.SearchUserIdOptional
import com.hamstoo.stream.facet._
import com.hamstoo.test.FutureHandler
import com.hamstoo.test.env.AkkaMongoEnvironment
import com.hamstoo.utils.{DataInfo, DurationMils, ExtendedTimeStamp, TIME_NOW, TimeStamp}
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.{ClassTag, classTag}

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
      .foldLeft(0.0) { case (agg, d) => agg + d._2.asInstanceOf[Datum[SearchResults.typ]].value._3.sum }
    x shouldBe (95.00 +- 0.01)
  }

  it should "compute AggregateSearchScore" in {
    sumFacetValues[AggregateSearchScore] shouldBe (9.67 +- 0.01)
  }

  it should "compute Recency" in {
    // see data-model/docs/RecencyTest.xlsx for an independent calculation of this value
    sumFacetValues[Recency] shouldBe (4.12 +- 0.01)
  }

  it should "compute Rating" in {
    sumFacetValues[Rating] shouldBe (12.5 +- 1e-10)
  }

  it should "compute ImplicitRating" in {
    sumFacetValues[ImplicitRating] shouldBe (7.58 +- 0.01)
  }

  it should "compute UserSimilarity" in {
    sumFacetValues[UserSimilarity] shouldBe (3.49 +- 0.01)
  }

  it should "compute ConfirmationBias" in {
    // close to 0 b/c the RWT and RWTa vectors are nearly co-linear
    sumFacetValues[ConfirmationBias] shouldBe (0.089 +- 0.001)
  }

  it should "compute EndowmentBias" in {
    // same vectors as ConfirmationBias lead to the same answer
    sumFacetValues[EndowmentBias] shouldBe (0.089 +- 0.001)
  }

  def sumFacetValues[A :ClassTag]: Double = {
    val facetName = classTag[A].runtimeClass.getSimpleName
    facetsSeq.filter(_._1 == facetName)
      .map { d => logger.info(s"\033[37m$facetName: $d\033[0m"); d }
      .foldLeft(0.0) { case (agg, d0) => d0._2 match { case d: Datum[Double] @unchecked => agg + d.value } }
  }

  // another way to test this is to uncomment the "uncomment this line" line in AggregateSearchScore which
  // causes this test to fail
  it should "complete even when there aren't any data (a \"duplicate key error\" may indicate a timeout)" in {
    val facetName = classOf[AggregateSearchScore].getSimpleName
    facetsEmpty.count(_._1 == facetName) shouldBe 0 // asserts that a timeout does not occur
  }

  it should "compute facet values for different 'search' and 'calling' users" in {
    val facetName = classOf[AggregateSearchScore].getSimpleName
    val scoreDiffUsers = facetsDiffUsers.filter(_._1 == facetName)
    val x = scoreDiffUsers
      .map { d => logger.info(s"\033[37m$facetName (different users): $d\033[0m"); d }
      .foldLeft(0.0) { case (agg, d0) => d0._2 match { case d: Datum[Double] @unchecked => agg + d.value } }
    x shouldBe (4.74 +- 0.01) // would be same as above 27.94 if not for access permissions
    facetsDiffUsers.size shouldBe 16 // this value increases by 2 each time a facet is added to FacetsModel.Default
    scoreDiffUsers.size shouldBe 2
  }

  val query: String = "some query "
  lazy val facetsSeq: Seq[OutType] = constructFacets(query, "a", differentUsers = false)
  lazy val facetsEmpty: Seq[OutType] = constructFacets("", "b", differentUsers = false)
  lazy val facetsDiffUsers: Seq[OutType] = constructFacets(query, "c", differentUsers = true)

  /**
    * `subj` is the text that allows the marks to be found by the Mongo Text Index search, so if it is empty no
    * marks will be found.
    * @param idSuffix  Used to prevent "duplicate key error" MonboDB exceptions.
    */
  def constructFacets(subj: String, idSuffix: String, differentUsers: Boolean): Seq[OutType] = {

    // config values that stream.ConfigModule will bind for DI
    val config = DataInfo.config
    val clockBegin: TimeStamp = new DateTime(2017, 12, 31, 0, 0).getMillis
    val clockEnd  : TimeStamp = new DateTime(2018,  1, 15, 0, 0).getMillis
    val clockInterval: DurationMils = (1 day).toMillis
    val mbSearchUserId: CallingUserId.typ = Some(UUID.fromString(s"11111111-1111-1111-1111-11111111111$idSuffix"))
    val mbCallingUserId: CallingUserId.typ =
      if (differentUsers) Some(UUID.fromString(s"22222222-2222-2222-2222-22222222222$idSuffix")) else mbSearchUserId

    // insert 5 marks with reprs into the database
    val nMarks = 5
    val baseVec = Seq(1.0, 2.0, 3.0)
    val baseVs = Map(VecEnum.PC1.toString -> baseVec)
    val baseRepr = Representation("", None, None, None, "", None, None, None, baseVs, None)

    //val b :: e :: Nil = Seq(ClockBegin.name, ClockEnd.name).map(config.getLong)
    val (b, e) = (clockBegin + clockInterval, clockEnd)
    (b to e by (e - b) / (nMarks - 1)).zipWithIndex.foreach { case (ts, i) =>

      // UserSimilarity only looks at the IDF-weighted vector of the reprs
      val vs = Map(VecEnum.IDF.toString -> Seq(ts.dt.getDayOfMonth.toDouble, 3.0, 2.0))
      val r = baseRepr.copy(id = s"r_${ts.Gs}_$idSuffix", vectors = vs, doctext = subj)
      val rating = if (i == 0) None else Some(i.toDouble)
      val aux = if (i == 2) None else Some(MarkAux(Some(Seq(RangeMils(0, i * 1000 * 60))), None, nOwnerVisits = Some(i)))

      val m = Mark(mbSearchUserId.get, s"m_${ts.Gs}_$idSuffix",
                   MarkData(subj, None, rating = rating),
                   aux = aux,
                   reprs = Seq(ReprInfo(r.id, ReprType.PUBLIC)),
                   timeFrom = ts)

      logger.info(s"\033[37m$m\033[0m")
      Await.result(marksDao.insert(m), 8 seconds)

      if (differentUsers) {
        val pub :: priv :: Nil = Seq(SharedWith.Level.PUBLIC, SharedWith.Level.PRIVATE).map((_, None))
        if (i == 1) marksDao.updateSharedWith(m, 0, pub, priv).futureValue // readOnly
        if (i == 2) marksDao.updateSharedWith(m, 0, priv, pub).futureValue // readWrite
      }

      if (i != nMarks - 1) Await.result(reprsDao.insert(r), 8 seconds) // skip one at the end for a better test of Join
    }

    // insert a UserStats so that the UserSimilarity facet can compute stuff
    val uvecs = Map[String, Vec](VecEnum.PC1.toString -> Seq(1, 2, 3),
                                 UserVecEnum.RWT.toString -> Seq(1, 2, 4),
                                 UserVecEnum.RWTa.toString -> Seq(1, 2, 5),
                                 UserVecEnum.USERc.toString -> Seq(1, 2, 4),
                                 UserVecEnum.PAGEc.toString -> Seq(1, 2, 5))
    val ustats = UserStats(mbCallingUserId.get, TIME_NOW, uvecs, Some(Seq("automobile", "generate", "keywords")))
    userStatsDao.insert(ustats).futureValue

    // this commented out line would have the same effect as below, but in the hamstoo project we already have an
    // appInjector and we need to call createChildInjector from it, so we do so here also to better mimic that scenario
    //val streamInjector = Guice.createInjector(ConfigModule(DataInfo.config), new StreamModule {

    // bind some stuff in addition to what's required by StreamModule
    val streamInjector = DataInfo.appInjector.createChildInjector(new StreamModule {

      override def configure(): Unit = {
        super.configure()
        logger.info(s"Configuring module: ${getClass.getName}")

        classOf[ExecutionContext] := system.dispatcher
        classOf[Materializer] := materializer
        classOf[ActorMaterializer] := materializer
        classOf[() => Future[DefaultDB]] := db

        //Val("clock.begin"):~ TimeStamp =~ clockBegin // alternative syntax? more like Scala?
        Clock.BeginOptional() := clockBegin
        Clock.EndOptional() := clockEnd
        Clock.IntervalOptional() := clockInterval
        QueryOptional() := query

        CallingUserId := mbCallingUserId
        if (differentUsers)
          SearchUserIdOptional() := mbSearchUserId

        LogLevelOptional() := Some(ch.qos.logback.classic.Level.TRACE)

        // fix this value (don't use default DateTime.now) so that computed values don't change every day
        Recency.CurrentTimeOptional() := new DateTime(2018, 4, 19, 0, 0).getMillis

        // we're 100% relying on semantic (vector similarity), marked-content so these inputs quadruple the output value
        AggregateSearchScore.SemanticWeight() := 1.0
        AggregateSearchScore.UserContentWeight() := 0.0
      }

      /** Finally, bind the model (via a provider so that AggregateSearchScore can be included). */
      @Provides @Singleton
      def provideModel(clock: Clock)
                      (implicit injector: Injector, mat: Materializer): FacetsModel = {
        val model = FacetsModel.Default(clock)(injector, mat)
        model.add[AggregateSearchScore]() // as of 2018-7-30, this is no longer part of FacetsModel.Default
        model
      }

      /** Provides a VectorEmbeddingsService for SearchResults to use via StreamModule.provideQueryVec. */
      @Provides @Singleton
      def provideVecSvc(query: QueryOptional, idfModel: IDFModel): VectorEmbeddingsService
                                                             = new VectorEmbeddingsService(null, idfModel) {
        override def countWords(words: Seq[String]): Future[Map[String, (Int, Vec)]] = {
          Future.successful(query.value.split(" ").map(_ -> (1, baseVec)).toMap)
        }
      }
    })

    logger.info(s"App injector: ${DataInfo.appInjector.hashCode}, stream injector: ${streamInjector.hashCode}")

    import net.codingwell.scalaguice.InjectorExtensions._
    val facetsModel = streamInjector.instance[FacetsModel]

    // this sink is no longer necessary now that filtering is happening after materialization
    //val sink: Sink[OutType, Future[Seq[OutType]]] = Flow[OutType].toMat(Sink.seq)(Keep.right)

    // causes "[error] a.a.OneForOneStrategy - CommandError[code=11600, errmsg=interrupted at shutdown" for some reason
    //facetsModel.run(sink).futureValue

    Await.result(facetsModel.flatRun(Sink.seq), 15 seconds)
  }
}