/*
package daos

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.{MongodExecutable, MongodStarter}
import de.flapdoodle.embed.process.runtime.Network
import models.auth.{Profile, User}
import play.api.inject.Injector
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.{Application, Mode}
import reactivemongo.play.json.collection.JSONCollection

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.reflectiveCalls // `dropCollection` uses reflection (at least it's easier to do than in Java! https://stackoverflow.com/questions/26787871/warning-about-reflective-access-of-structural-type-member-in-scala)


object DaoSpecResources {

  // "please store Starter or RuntimeConfig in a static final field
  // if you want to use artifact store caching (or else disable caching)"
  //   [https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo]
  private val starter: MongodStarter = MongodStarter.getDefaultInstance

  // it's nice to have a global timeout parameter so that it can be easily changed when debugging,
  // but ensure it's big enough to account for the lazy instantiation of the in-memory database
  val TIMEOUT: FiniteDuration = 20 seconds

  val MONGO_HOST = "localhost"
  val MONGO_PORT = 27018

  // in-memory 'Embedded MongoDB' instance
  private lazy val mongodExe: MongodExecutable = DaoSpecResources.starter.prepare(
    new MongodConfigBuilder()
      .version(Version.Main.PRODUCTION)
      .net(new Net(MONGO_HOST, MONGO_PORT, Network.localhostIsIPv6()))
      .build())
  private lazy val mongod: MongodProcess = mongodExe.start

  // must use the same appBuilder object to `build` the application and construct the
  // `injector`, the configuration parameters (particularly `mongodb.uri`) are required
  // for both
  private lazy val appBuilder =
    new GuiceApplicationBuilder()
      .configure("mongodb.uri" -> s"mongodb://$MONGO_HOST:$MONGO_PORT/test")
      .configure("play.cache.createBoundCaches" -> false)
      .in(Mode.Test)
  private lazy val fakeApp: Application = appBuilder.build()

  /**
    * This `lazy val` is similar to `org.specs2.specification.BeforeAll.beforeAll` except that
    * it executes "before ALL tests" rather than "before all tests in each class".  This is
    * also the reason why DaoSpecResources is no longer an (extendable) trait but is now instead
    * a singleton object, which will be instantiated only once for each execution of the test
    * suite.
    */
  lazy val injector: Injector = {

    // trigger lazy instantiation
    mongod
    fakeApp

    // injector to be used by tests to get instances of the things they're testing
    appBuilder.injector
  }

  // I'm not sure why the "application" doesn't include the "server"; for some reason the server
  // has to be started separately.  What is it without it?  Update: It seems the application can
  // be a client also, as in `appBuilder.injector.instanceOf(classOf[WSClient])`.
  // With `Mode.Test` the "NettyServer - Listening for HTTP[S] on /0:0:0:0:0:0:0:0:9[000|443]" log
  // messages are not displayed, but the (in-process) behavior is the same.  It can be switched
  // to `Mode.Prod` however to see those messages and to confirm listening on SSL port also.
  //   https://stackoverflow.com/questions/33312581/use-play-framework-as-a-component
  //   https://www.playframework.com/documentation/2.5.x/ScalaEmbeddingPlay
//  private lazy val svrCfg = ServerConfig(sslPort = fakeApp.configuration.getInt("play.server.https.port"),
//                                         mode = Mode.Test)
//  lazy val server: NettyServer = play.core.server.NettyServer.fromApplication(fakeApp, svrCfg)

  /** Shutdown the in-memory database. */
//  override protected def finalize(): Unit = {
//    mongod.stop()
//    mongodExe.stop()
//    super.finalize()
//  }

  // https://tomlee.co/2007/11/anonymous-type-acrobatics-with-scala/
  type HasColl = { val coll: Future[JSONCollection] }

  def setupUser(userDao: UserDao, user: User): Unit = {
    val future = for {
      _ <- dropCollection(userDao.asInstanceOf[HasColl])
      unit <- userDao.save(user) map (_ => ())
    } yield unit
    Await.result(future, TIMEOUT)
  }

  // "View bounds are deprecated: SI-7629. It's better to replace them with implicit parameters."
  // This function, conceivably, could just do away with the implicit conversion to HasColl and
  // instead perform an explicit `dao.asInstance[HasColl]`, but that would be checked at runtime
  // while the former is checked at compile time.
  //def dropCollection[T <% HasColl](dao: T): Future[Unit] =
  def dropCollection[T](dao: T)(implicit ev: T => HasColl): Future[Unit] =
    dao.coll map (_.drop(failIfNotFound = true))

  val loginInfo = LoginInfo("credentials", "john.doe@gmail.com")
  val oauth1LoginInfo = LoginInfo("Twitter", "119625")
  val oauth2LoginInfo = LoginInfo("Facebook", "119625")
  val passwordInfo = PasswordInfo("BCrypt", "kittens", None)

  val testPasswordProfile = Profile(
    loginInfo = loginInfo,
    confirmed = false,
    email = Some("john.doe@eeeeeeeeeeeeeeeeemail.com"), // note different from loginInfo.providerKey
    firstName = Some("John"),
    lastName = Some("Doe"),
    fullName = Some("John Doe"),
    avatarUrl = Some("http://www.gravatar.com"))

  val testOauth1Profile = Profile(
    loginInfo = oauth1LoginInfo,
    confirmed = false,
    email = None,
    firstName = Some("John"),
    lastName = Some("Doe"),
    fullName = Some("John Doe"),
    avatarUrl = Some("http://www.gravatar.com"))

  val testOauth2Profile = Profile(
    loginInfo = oauth2LoginInfo,
    confirmed = false,
    email = None,
    firstName = Some("John"),
    lastName = Some("Doe"),
    fullName = Some("John Doe"),
    avatarUrl = Some("http://www.gravatar.com"))
}
*/
