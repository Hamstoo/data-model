/*
package daos

import java.util.UUID

import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import models.auth.{User, UserData}
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.concurrent.Execution.Implicits.defaultContext // implicit ExecutionContext

import scala.concurrent.{Await, Future}


class OAuth1InfoDaoSpec extends Specification {

  import DaoSpecResources._

  trait scope extends Scope {
    val userDao: UserDao = injector instanceOf classOf[MongoUserDao]
    setupUser(userDao, User(
      UUID randomUUID(),
      UserData(),
      testPasswordProfile.copy(passwordInfo = Option apply passwordInfo) :: testOauth1Profile :: Nil))
    val oauth1InfoDao: OAuth1InfoDao = injector instanceOf classOf[MongoOAuth1InfoDao]
  }

  val oauth1Info = OAuth1Info("token", "shhhh")

  "OAuth1InfoDao" should {
    "* add and remove new OAuth1 information" in new scope {

      val fut: Future[Option[OAuth1Info]] = for {
        _ <- oauth1InfoDao.save(oauth1LoginInfo, oauth1Info)
        mbOauth1Info <- oauth1InfoDao find oauth1LoginInfo
      } yield mbOauth1Info
      Await.result(fut, TIMEOUT) must beSome(oauth1Info)

      val fut2: Future[Option[OAuth1Info]] = for {
        _ <- oauth1InfoDao remove oauth1LoginInfo
        mbOauth1Info <- oauth1InfoDao find oauth1LoginInfo
      } yield mbOauth1Info
      Await.result(fut2, TIMEOUT) must beNone
    }
  }
}
*/
