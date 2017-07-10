/*
package daos

import java.util.UUID

import models.auth.{User, UserData}
import org.specs2.specification.Scope
import org.specs2.mutable.Specification
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future}


class UserDaoSpec extends Specification {

  // each test drops the `users` collection from the database so they have to be run
  // sequentially, it might be better to have the tests create different users
  // so that this requirement can be relaxed (and so `dropCollection` can be removed)
  sequential // (what odd syntax)

  import DaoSpecResources._

  // "extend Scope to be used as an Example body"
  //   [https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala]
  trait scope extends Scope {
    val userDao: UserDao = injector instanceOf classOf[MongoUserDao]
    Await.ready(dropCollection(userDao.asInstanceOf[MongoUserDao]), TIMEOUT)
  }

  lazy val credentialsTestUser =
    User(UUID randomUUID(), UserData(), testPasswordProfile.copy(passwordInfo = Option(passwordInfo)) :: Nil)

  "UserDao" should {
    "* save users and find them by userId" in new scope {
      val fut: Future[Option[User]] = for {
        _ <- userDao save credentialsTestUser
        mbUser <- userDao retrieve credentialsTestUser.id
      } yield mbUser
      Await.result(fut, TIMEOUT) must beSome(credentialsTestUser)
    }

    "* save users and find them by loginInfo" in new scope {
      val fut: Future[Option[User]] = for {
        _ <- userDao save credentialsTestUser
        mbUser <- userDao retrieve testPasswordProfile.loginInfo
      } yield mbUser
      Await.result(fut, TIMEOUT) must beSome(credentialsTestUser)
    }

    "* save users and find them by email" in new scope {
      val fut: Future[Option[User]] = for {
        _ <- userDao save credentialsTestUser
        mbUser <- userDao retrieve testPasswordProfile.email.get
      } yield mbUser
      Await.result(fut, TIMEOUT) must beSome(credentialsTestUser)
    }

    "* confirm a profile, link a new profile to an existing user, update an existing profile" in new scope {
      val newName: String = testPasswordProfile.firstName.get + "ny" // i.e. "Johnny"

      val fut: Future[User] = for {
        _ <- userDao save credentialsTestUser
        user <- userDao confirm loginInfo
        user <- userDao update ((user profileFor loginInfo get) copy (firstName = Some(newName)))
        user <- userDao link(user, testOauth1Profile)
      } yield user

      val user = Await.result(fut, TIMEOUT)

      (user.profiles exists (_.confirmed)) === true
      user.profiles.length mustEqual 2
      user.profiles map (_.loginInfo) must contain(loginInfo, oauth1LoginInfo)
      (user.profiles exists (_.firstName contains newName)) === true
    }
  }
}
*/
