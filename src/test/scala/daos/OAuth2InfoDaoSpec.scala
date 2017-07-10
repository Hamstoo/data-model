/*
package daos

import java.util.UUID

import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import models.auth.{User, UserData}
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.concurrent.Execution.Implicits.defaultContext // implicit ExecutionContext

import scala.concurrent.{Await, Future}


class OAuth2InfoDaoSpec extends Specification {

  import DaoSpecResources._

  trait scope extends Scope {
    val userDao: UserDao = injector instanceOf classOf[MongoUserDao]
    setupUser(userDao, User(
      UUID randomUUID(),
      UserData(),
      testPasswordProfile.copy(passwordInfo = Option apply passwordInfo) :: testOauth2Profile :: Nil))
    val oauth2InfoDao: OAuth2InfoDao = injector instanceOf classOf[MongoOAuth2InfoDao]
  }

  val oauth2Info = OAuth2Info("token")

  "OAuth2InfoDao" should {
    "* add and remove new OAuth2 information" in new scope {
      
      val fut: Future[Option[OAuth2Info]] = for {
        _ <- oauth2InfoDao.save(oauth2LoginInfo, oauth2Info)
        mbOauth2Info <- oauth2InfoDao find oauth2LoginInfo
      } yield mbOauth2Info
      Await.result(fut, TIMEOUT) must beSome(oauth2Info)
      
      val fut2: Future[Option[OAuth2Info]] = for {
        _ <- oauth2InfoDao remove oauth2LoginInfo
        mbOauth2Info <- oauth2InfoDao find oauth2LoginInfo
      } yield mbOauth2Info
      Await.result(fut2, TIMEOUT) must beNone
    }
  }
}
*/
