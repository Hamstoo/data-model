/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.dataset

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.daos.{MarkDao, RepresentationDao, UserDao}
import com.hamstoo.models._
import com.hamstoo.services.IDFModel
import com.hamstoo.stream._
import com.hamstoo.utils.{ObjectId, TimeStamp}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * A stream of marks, sourced from a user's search, though search terms are not required.
  */
@com.google.inject.Singleton
class MarksStream @Inject()(@Named(CallingUserId.name) callingUserId: CallingUserId.typ,
                            @Named(Query2Vecs.name) mbQuery2Vecs: Query2Vecs.typ,
                            mbSearchUserId: MarksStream.SearchUserIdOptional,
                            labels: MarksStream.SearchLabelsOptional)
                           (implicit clock: Clock,
                            mat: Materializer,
                            markDao: MarkDao,
                            reprDao: RepresentationDao,
                            userDao: UserDao,
                            idfModel: IDFModel)
    extends PreloadSource[Mark](loadInterval = (183 days).toMillis) {

  import MarksStream._

  val searchUserId: UUID = mbSearchUserId.value.getOrElse(callingUserId)
  val tags: Set[String] = labels.value

  /** PreloadSource interface.  `begin` should be inclusive and `end`, exclusive. */
  override def preload(begin: TimeStamp, end: TimeStamp): PreloadType[Mark] = {

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = mbQuery2Vecs.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val mbSearchTermVecs = mbQuery2Vecs.map(_._2)

    // if the search & calling users are the same then only show MarkRefs in the search results
    // if query words exist (o/w we're simply listing the calling user's marks perhaps with a facet arg),
    // this behavior matches that of the `else` clause in MarksController.list
//    val includeMarkRefs = mbSearchUserId.value.exists(_ != callingUserId) || mbQuery2Vecs.nonEmpty
//    logger.debug(s"includeMarkRefs = $includeMarkRefs")

    // get a couple of queries off-and-running before we start Future-flatMap-chaining

    // Mongo Text Index search (e.g. includes stemming) over `entries` collection (and filter results by labels)
    val fscoredMs = mbQuerySeq.mapOrEmptyFuture { w =>
      markDao.search(Set(searchUserId), w, begin = Some(begin), end = Some(end))
        .map(_.toSeq.filter(m => m.markRef.isEmpty && m.hasTags(tags)))
        .flatMap(filterAuthorizedRead(_, callingUserId))
    }

    // every single non-ref mark (refs are handled below, and included only if they match search terms)
    val funscoredMs = markDao.retrieve(searchUserId, tags = tags, begin = Some(begin), end = Some(end))
                        .map(_.filter(_.markRef.isEmpty))
                        .flatMap(filterAuthorizedRead(_, callingUserId))

    for {
      // MarkRefs (i.e. marks that aren't owned by the calling user)
      id2Ref <- /*if (!includeMarkRefs) Future.successful(Map.empty[ObjectId, MarkRef])
                else*/ markDao.retrieveRefed(callingUserId, begin = Some(begin), end = Some(end))
      candidateRefs <- markDao.retrieveInsecureSeq(id2Ref.keys.toSeq, begin = Some(begin), end = Some(end))
                         .map(maskAndFilterTags(_, tags, id2Ref, User(callingUserId)))

      // if the search/calling users are different, then only include calling user's MarkRefs that refer to search
      // user's marks (i.e. exclude marks that were shared _to_ the search user) because they're owned by others with
      // (probably) no connection to the calling user; but if the search/calling user are the same, then include
      // all of calling user's MarkRefs
      refUserIds = if (searchUserId != callingUserId) Set(searchUserId) else candidateRefs.map(_.userId).toSet
      refMarkIds = id2Ref.keySet

      // perform MongoDB Text Index search over referenced marks (i.e. marks owned by other users) and then impose
      // any rating or label changes this user has made on top of those references
      fscoredRefs = mbQuerySeq.mapOrEmptyFuture { w =>
        markDao.search(refUserIds, w, ids = Some(refMarkIds))
          .map(maskAndFilterTags(_, tags, id2Ref, User(callingUserId)))
      }

      // "candidates" are ALL of the marks viewable to the callingUser (with the appropriate labels), which will
      // include even those that were not returned by MongoDB Text Index search
      unscoredMs <- funscoredMs.map(_ ++ candidateRefs)

      sms <- fscoredMs; srefs <- fscoredRefs
      scoredUngroupedMs = sms.zip(srefs).map { case (ms, refs) => ms ++ refs }

    } yield {

      /** Weight `entries` collection search scores by both the number of repetitions of the query word and its IDF. */
      def wgtMkScore(qm: ((String, Int), Mark)): Double =
        qm._1._2 * idfModel.transform(qm._1._1) * qm._2.score.get // #reps * idf * score

      // `scoredUngroupedMs` will contain duplicate marks with different scores for different search terms so join them,
      // start by broadcasting each query word (qw) in `cleanedQuery` to its respective marks (qms)
      val scoredMs = mbCleanedQuery.fold(Iterable.empty[Mark]) { cleanedQuery =>
        cleanedQuery.view.zip(scoredUngroupedMs).flatMap { case (qw, qms) => qms.map((qw, _)) }
          .groupBy(_._2.id).values // group query words by mark ID
          .map { seqvw => seqvw.head._2.copy(score = Some(seqvw.map(wgtMkScore).sum)) }
      }

      // performing filterNot rather than relying on set union because scoredMs will have not only score
      // populated but could also have different labels/rating due to MarkRef masking
      val entries = scoredMs.toSet ++ unscoredMs.filterNot(c => scoredMs.exists(_.id == c.id))
      entries.map(m => Datum(m, MarkId(m.id), m.timeFrom)).to[immutable.Seq]
    }
  }
}

object MarksStream {

  val logger = Logger(classOf[MarksStream])

  // allows marks by one user (the search user) to be searched by another user (the calling user)
  case class SearchUserIdOptional() extends OptionalInjectId[Option[UUID]]("search.user.id", None)

  // allows marks search to filter for specific labels
  case class SearchLabelsOptional() extends OptionalInjectId[Set[String]]("labels", Set.empty[String])

  /**
    * Filter for marks that the calling user is authorized to read.  This function relies on the marks being in
    * a linear Seq, so don't think you can just change it to be an Iterable--i.e. a Set won't work!
    */
  def filterAuthorizedRead(ms: Seq[Mark], callingUserId: UUID)
                          (implicit userDao: UserDao, ec: ExecutionContext): Future[Seq[Mark]] =
    Future.sequence(ms.map { _.isAuthorizedRead(User(callingUserId))(userDao, implicitly) })
      .map { auths =>
        val filtered = ms.zip(auths).filter(_._2).map(_._1)
        if (ms.size > filtered.size) logger.debug(s"Filtered ${ms.size} marks down to ${filtered.size}")
        filtered
      }

  /**
    * Referenced marks need to have their labels unioned with those of their MarkRefs before they can be filtered.
    * Non-referenced marks can just pass directly to the label filtering stage untouched.
    */
  def maskAndFilterTags(marks: Iterable[Mark],
                        tags: Set[String],
                        id2Ref: Map[ObjectId, MarkRef],
                        callingUser: Option[User]): Iterable[Mark] = {

    def maskOrElse(m: Mark) = m.mask(id2Ref.get(m.id), callingUser)

    marks.map(maskOrElse).filter(_.hasTags(tags))
  }
}
