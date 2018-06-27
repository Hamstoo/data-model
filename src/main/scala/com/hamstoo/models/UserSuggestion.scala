package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.daos.UserDao
import com.hamstoo.utils.{TIME_NOW, TimeStamp}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/***
  * User suggestions for during search.
  * For determining recent sharees during sharing see UserDao.retrieveRecentSharees.
  * 
  * @param ownerUserId  user that made share
  * @param sharee  to whom username share was made (email address or username) or None for completely public shares
  * @param shareeUsername  if sharee is the email address of a user, then this field holds that user's username
  * @param ts        timestamp, which can be updated (which is why we don't call this field `created`)
  */
case class UserSuggestion(ownerUserId: UUID,
                          ownerUsername: String,
                          sharee: Option[String],
                          shareeUsername: Option[String] = None,
                          ts: TimeStamp = TIME_NOW) {
}

object UserSuggestion extends BSONHandlers {

  /** Construct a UserSuggestion and lookup usernames in the process. */
  def xapply(ownerUserId: UUID, sharee: Option[String])(implicit userDao: UserDao): Future[UserSuggestion] = {
    userDao.retrieveById(ownerUserId).map(_.flatMap(_.userData.username).getOrElse("")) flatMap { ownerUsername =>
      sharee.fold(
        Future.successful(new UserSuggestion(ownerUserId, ownerUsername, None, None))
      ){ emailOrUsername =>
        for {
          mbUserByUsername <- userDao.retrieveByUsername(emailOrUsername)
          mbUser <- mbUserByUsername.fold(userDao.retrieveByEmail(emailOrUsername))(u => Future.successful(Some(u)))
        } yield {
          new UserSuggestion(ownerUserId, ownerUsername, sharee, mbUser.flatMap(_.userData.username))
        }
      }
    }
  }

  val OWNER_ID: String = nameOf[UserSuggestion](_.ownerUserId)
  val OWNER_UNAME: String = nameOf[UserSuggestion](_.ownerUsername)
  val SHAREE: String = nameOf[UserSuggestion](_.sharee)
  val SHAREE_UNAME: String = nameOf[UserSuggestion](_.shareeUsername)
  val TIMESTAMP: String = nameOf[UserSuggestion](_.ts)

  implicit val fmt: BSONDocumentHandler[UserSuggestion] = Macros.handler[UserSuggestion]
}


