/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.utils

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.name.Named
import com.google.inject.{Guice, Injector, Provides, Singleton}
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.models._
import com.hamstoo.stream.config.{BaseModule, ConfigModule}
import com.mohiva.play.silhouette.api.LoginInfo
import com.typesafe.config.{Config, ConfigFactory}
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSClient
import reactivemongo.api.{DefaultDB, MongoConnection}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Trait that contain test information
  */
object DataInfo {

  // default mongo port, override if needed
  val mongoPort: Int = 12345

  // https://stackoverflow.com/questions/24153614/scala-write-value-to-typesafe-config-object
  // https://alvinalexander.com/scala/how-to-create-multiline-strings-heredoc-in-scala-cookbook
  lazy val config: Config = ConfigFactory.parseString(s"""vectors.link = "http://localhost:5000"
                                                         |idfs.resource = idfs/text8.json.zip
                                                         |mongodb.uri = "mongodb://localhost:$mongoPort/hamstoo?authMode=scram-sha1"
                                                         |""".stripMargin)

  // create a Guice object graph configuration/module and instantiate it to an injector
  lazy val appInjector: Injector = Guice.createInjector(ConfigModule(config))

  /**
    * Unlike repr-engine's DBDriverTestSuite, data-model tests get their Materializer by extending
    * AkkaMongoEnvironment, which is why it has to be passed in here.
    */
  def createExtendedInjector()(implicit materializer: Materializer, ec: ExecutionContext): Injector =
    appInjector.createChildInjector(new BaseModule {
      override def configure(): Unit = {
        super.configure()
        classOf[Materializer] := materializer
        classOf[ExecutionContext] := ec
      }

      @Provides @Singleton
      def provideDB(@Named("mongodb.uri") mongoDbUri: String): () => Future[DefaultDB] = {
        val (dbConn: MongoConnection, dbName: String) = getDbConnection(mongoDbUri)
        () => dbConn.database(dbName)
      }

      @Provides @Singleton
      def provideWSClient(implicit materializer: Materializer): WSClient = AhcWSClient()
    })

  // these used to be `userId` and `markId` constants but were changed to functions to avoid collisions between tests
  def constructUserId(): UUID = UUID.randomUUID()
  def constructMarkId(): String = generateDbId(Mark.ID_LENGTH)

  // "val" to distinguish it from `def userId` (which has now been changed to `def constructUserId()`)
  val valUserId: UUID = constructUserId()

  val reprInfoA = ReprInfo("aReprId", ReprType.PUBLIC)
  val reprInfoPrivB = ReprInfo("bPrivReprId", ReprType.PRIVATE)
  val reprInfoPubB = ReprInfo("bPubReprId", ReprType.PUBLIC)

  val mdA = MarkData("a subject", Some("http://a.com"), Some(3.0), Some(Set("atag")), Some("a comment"))
  val mdB = MarkData("b subject", Some("http://b.com"), Some(4.0), Some(Set("btag")), Some("b comment"))
  val mA = Mark(valUserId, mark = mdA, reprs = Seq(reprInfoA))
  val mB = Mark(valUserId, mark = mdB, reprs = Seq(reprInfoPrivB, reprInfoPubB))

  val loginInfoA = LoginInfo("GProviderBookMailFace", "some_provider_key_A")
  val loginInfoB = LoginInfo("GProviderBookMailFace", "some_provider_key_B")
  val userA = User(constructUserId(), UserData(), Profile(loginInfoA, confirmed = true, Some("a@mail"), None, None, None) :: Nil)
  val userB = User(constructUserId(), UserData(), Profile(loginInfoB, confirmed = true, Some("b@mail"), None, None, None) :: Nil)

  val crazyUrl = "https://translate.google.com.ua/#ru/en/%D0%94%D0%BE%D0%B1%D1%80%D1%8B%D0%B9%20%D0%B4%D0%B5%D0%BD%D1%8C!%0A%D0%9C%D1%8B%20%D0%BE%D0%B7%D0%BD%D0%B0%D0%BA%D0%BE%D0%BC%D0%B8%D0%BB%D0%B8%D1%81%D1%8C%20%D1%81%20%D0%B2%D0%B0%D1%88%D0%B8%D0%BC%D0%B8%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F%D0%BC%D0%B8%20%D0%BD%D0%B0%20%D0%BD%D0%B0%D1%88%20%D0%BE%D1%82%D0%B7%D1%8B%D0%B2%20%20%D0%B8%20%D0%BE%D1%87%D0%B5%D0%BD%D1%8C%20%D0%BE%D0%B3%D0%BE%D1%80%D1%87%D0%B5%D0%BD%D1%8B%20%D1%82%D0%B5%D0%BC%2C%20%D1%87%D1%82%D0%BE%20%D0%B2%D0%BB%D0%B0%D0%B4%D0%B5%D0%BB%D0%B5%D1%86%20%20Bronzino%20Apartments%20%D0%BD%D0%B5%20%D1%82%D0%BE%D0%BB%D1%8C%D0%BA%D0%BE%20%D0%B0%D0%B3%D1%80%D0%B5%D1%81%D1%81%D0%B8%D0%B2%D0%B5%D0%BD%20%D0%B8%20%D0%BD%D0%B5%D1%83%D1%80%D0%B0%D0%B2%D0%BD%D0%BE%D0%B2%D0%B5%D1%88%D0%B5%D0%BD%2C%20%D0%BD%D0%BE%20%D0%B8%20%D0%BB%D0%B6%D0%B8%D0%B2.%20%D0%98%20%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%BD%D1%8B%D0%B5%20%D0%BE%D1%82%20%D0%B2%D0%B0%D1%81%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F"

  val emptyMarkData = MarkData("", None, None, None, None)
  def withComment(comment: String, init: MarkData = emptyMarkData): MarkData = init.copy(comment = Some(comment))

  val inlineNotePos = InlineNote.Position(Some("text"), "path", None, None, 0, 0)
}
