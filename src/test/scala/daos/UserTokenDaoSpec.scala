/*
package daos

import java.util.UUID

import com.hamstoo.daos.MongoUserTokenDao
import models.auth.UserToken
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.{Await, Future}


class UserTokenDaoSpec extends Specification {

  // each test drops the `users` collection from the database so they have to be run
  // sequentially, it might be better to have the tests create different users
  // so that this requirement can be relaxed (and so `dropCollection` can be removed)
  sequential // (what odd syntax)

  import DaoSpecResources._

  // "extend Scope to be used as an Example body"
  //   [https://github.com/etorreborre/specs2/blob/SPECS2-3.8.9/examples/src/test/scala/examples/UnitSpec.scala]
  trait scope extends Scope {
    val userTokenDao: UserTokenDao = injector instanceOf classOf[MongoUserTokenDao]
    Await.ready(dropCollection(userTokenDao.asInstanceOf[MongoUserTokenDao]), TIMEOUT)
  }

  val token = UserToken(UUID randomUUID(), UUID randomUUID(), "john.doe@gmail.com", new DateTime(), isSignUp = true)

  "UserTokenDao" should {
    "* persist, find, and remove a token" in new scope {

      val fut: Future[Option[UserToken]] = for {
        _ <- userTokenDao save token
        mbToken <- userTokenDao find token.id
      } yield mbToken
      Await.result(fut, TIMEOUT) must beSome(token)

      val fut2: Future[Option[UserToken]] = for {
        _ <- userTokenDao remove token.id
        mbToken <- userTokenDao find token.id
      } yield mbToken
      Await.result(fut2, TIMEOUT) must beNone
    }
  }
}
*/
