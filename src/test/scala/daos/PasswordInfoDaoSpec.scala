/*
package daos

import java.util.UUID

import com.mohiva.play.silhouette.api.util.PasswordInfo
import models.auth.{User, UserData}
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future}


class PasswordInfoDaoSpec extends Specification {

  // each test drops the `users` collection from the database so they have to be run
  // sequentially, it might be better to have the tests create different users
  // so that this requirement can be relaxed (and so `dropCollection` can be removed)
  sequential // (what odd syntax)

  import DaoSpecResources._

  // "extend Scope to be used as an Example body"
  //   [https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala]
  trait scope extends Scope {
    val userDao: UserDao = injector instanceOf classOf[MongoUserDao]
    val passwordInfoDao: PasswordInfoDao = injector instanceOf classOf[MongoPasswordInfoDao]
    setupUser(userDao, User(UUID randomUUID(), UserData(), testPasswordProfile :: Nil))
  }

  "PasswordInfoDao" should {
    "* add and remove new credentials information" in new scope {

      val fut: Future[Option[PasswordInfo]] = for {
        _ <- passwordInfoDao.save(loginInfo, passwordInfo)
        mbPasswordInfo <- passwordInfoDao find loginInfo
      } yield mbPasswordInfo
      Await.result(fut , TIMEOUT) must beSome(passwordInfo)

      val fut2: Future[Option[PasswordInfo]] = for {
        _ <- passwordInfoDao remove loginInfo
        mbPasswordInfo <- passwordInfoDao find loginInfo
      } yield mbPasswordInfo
      Await.result(fut2, TIMEOUT) must beNone
    }
  }
}
*/
