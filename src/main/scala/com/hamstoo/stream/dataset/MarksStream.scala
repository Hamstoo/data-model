/*
 * Copyright (C) 2017-2018 Hamstoo Corp. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.dataset

import java.util.UUID

import akka.stream.Materializer
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
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
class MarksStream @Inject() (@Named(CallingUserId.name) callingUserId: CallingUserId.typ,
                             @Named(Query2VecsOptional.name) mbQuery2Vecs: Query2VecsOptional.typ,
                             mbSearchUserId: MarksStream.SearchUserIdOptional,
                             labels: MarksStream.SearchLabelsOptional)
                            (implicit clock: Clock, materializer: Materializer, ec: ExecutionContext,
                             marksDao: MongoMarksDao,
                             reprDao: MongoRepresentationDao,
                             userDao: MongoUserDao,
                             idfModel: IDFModel)
    extends PreloadSource[MSearchable]((700 days).toMillis) {

  import MarksStream._
  //override val logger: Logger = MarksStream.logger // causes a NullPointerException (kws: NPE)
  val logger1: Logger = MarksStream.logger

  val searchUserId: UUID = mbSearchUserId.value.getOrElse(callingUserId)
  val tags: Set[String] = labels.value

  /** PreloadSource interface.  `begin` should be inclusive and `end`, exclusive. */
  override def preload(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[MSearchable]]] = {

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = mbQuery2Vecs.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val mbSearchTermVecs = mbQuery2Vecs.map(_._2)

    // get a couple of queries off-and-running before we start Future-flatMap-chaining

    // Mongo Text Index search (e.g. includes stemming) over `entries` collection (and filter results by labels)
    val fscoredMs = mbQuerySeq.mapOrEmptyFuture { w =>
      marksDao.search(Set(searchUserId), w, begin = Some(begin), end = Some(end))
        .map(_.toSeq.filter(_.hasTags(tags))).flatMap(filterAuthorizedRead(_, callingUserId))
    }

    // every single mark with an existing representation
    val funscoredMs = marksDao.retrieveRepred(searchUserId, tags = tags, begin = Some(begin), end = Some(end))
                        .flatMap(filterAuthorizedRead(_, callingUserId))

    for {
      // candidate referenced marks (i.e. marks that aren't owned by the calling user)
      id2Ref <- marksDao.retrieveRefed(callingUserId, begin = Some(begin), end = Some(end))
      candidateRefs <- marksDao.retrieveInsecureSeq(id2Ref.keys.toSeq, begin = Some(begin), end = Some(end))
        .map(maskAndFilterTags(_, tags, id2Ref, User(callingUserId)))

      // don't show the calling user marks that were shared to the search user (i.e. that the search user doesn't own)
      refUserIds = if (searchUserId == callingUserId) candidateRefs.map(_.userId).toSet else Set(searchUserId)
      refMarkIds = id2Ref.keySet

      // perform MongoDB Text Index search over referenced marks (i.e. marks owned by other users) and then impose
      // any rating or label changes this user has made on top of those references
      fscoredRefs = mbQuerySeq.mapOrEmptyFuture { w =>
        marksDao.search(refUserIds, w, ids = refMarkIds).map(maskAndFilterTags(_, tags, id2Ref, User(callingUserId)))
      }

      // "candidates" are ALL of the marks viewable to the callingUser (with the appropriate labels), which will
      // include those that were not returned by MongoDB Text Index search
      unscoredMs <- funscoredMs.map(_ ++ candidateRefs)

      sms <- fscoredMs; srefs <- fscoredRefs
      scoredUngroupedMs = sms.zip(srefs).map { case (ms, refs) => ms ++ refs }

    } yield {

      /** Weight `entries` collection search scores by both the number of repetitions of the query word and its IDF. */
      def wgtMkScore(qm: ((String, Int), MSearchable)): Double =
        qm._1._2 * idfModel.transform(qm._1._1) * qm._2.score.get // #reps * idf * score

      // `scoredUngroupedMs` will contain duplicate marks with different scores for different search terms so join them,
      // start by broadcasting each query word (qw) in `cleanedQuery` to its respective marks (qms)
      val scoredMs = mbCleanedQuery.fold(Iterable.empty[MSearchable]) { cleanedQuery =>
        cleanedQuery.view.zip(scoredUngroupedMs).flatMap { case (qw, qms) => qms.map((qw, _)) }
          .groupBy(_._2.id).values // group query words by mark ID
          .map { seqvw => seqvw.head._2.xcopy(score = Some(seqvw.map(wgtMkScore).sum)) }
      }

      // performing filterNot rather than relying on set union because scoredMs will have not only score
      // populated but could also have different labels/rating due to MarkRef masking
      val entries = scoredMs.toSet ++ unscoredMs.filterNot(c => scoredMs.exists(_.id == c.id))
      entries.map(m => Datum(m, MarkId(m.id), m.timeFrom))
    }
  }
}

object MarksStream {

  val logger = Logger(classOf[MarksStream])

  case class SearchUserIdOptional() extends OptionalInjectId[Option[UUID]] {
    final val name = "search.user.id"
    @Inject(optional = true) @Named(name) val value: Option[UUID] = None
  }

  case class SearchLabelsOptional() extends OptionalInjectId[Set[String]] {
    final val name = "labels"
    @Inject(optional = true) @Named(name) val value: Set[String] = Set.empty[String]
  }

  /**
    * Filter for marks that the calling user is authorized to read.  This function relies on the marks being in
    * a linear Seq, so don't think you can just change it to be an Iterable--i.e. a Set won't work!
    */
  def filterAuthorizedRead(ms: Seq[MSearchable], callingUserId: UUID)
                          (implicit userDao: MongoUserDao, ec: ExecutionContext): Future[Seq[MSearchable]] =
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
  def maskAndFilterTags(marks: Iterable[MSearchable],
                        tags: Set[String],
                        id2Ref: Map[ObjectId, MarkRef],
                        callingUser: Option[User]): Iterable[MSearchable] = {

    def maskOrElse(m: MSearchable) = m.mask(id2Ref.get(m.id), callingUser)

    marks.map(maskOrElse).filter(_.hasTags(tags))
  }
}
