/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.stream.dataset

import java.util.UUID

import akka.stream.Materializer
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.daos.{MarkDao, UserDao}
import com.hamstoo.models._
import com.hamstoo.services.IDFModel
import com.hamstoo.stream.Data.Data
import com.hamstoo.stream._
import com.hamstoo.utils.{ObjectId, TimeStamp}
import org.slf4j.LoggerFactory
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * A stream of marks, sourced from a user's search, though search terms are not required.
  */
@com.google.inject.Singleton
class MarksStream @Inject()(@Named(CallingUserId.name) mbCallingUserId: CallingUserId.typ,
                            @Named(Query2Vecs.name) mbQuery2Vecs: Query2Vecs.typ,
                            mbSearchUserId: MarksStream.SearchUserIdOptional,
                            labels: MarksStream.SearchLabelsOptional,
                            logLevel: LogLevelOptional)
                           (implicit clock: Clock,
                            mat: Materializer,
                            markDao: MarkDao,
                            userDao: UserDao,
                            idfModel: IDFModel)
    extends PreloadSource[Mark](loadInterval = (183 days).toMillis) {

  /** PreloadSource interface.  `begin` should be inclusive and `end`, exclusive. */
  override def preload(begin: TimeStamp, end: TimeStamp): PreloadType[Mark] = {
    MarksStream.load(mbCallingUserId, mbQuery2Vecs, mbSearchUserId.value, labels.value,
                     Some(begin), Some(end), logLevel.value)
  }
}

object MarksStream {

  val logger = Logger(classOf[MarksStream])

  /** This implementation was moved into the companion object so that it can be accessed from MarksController.search. */
  def load(mbCallingUserId: Option[UUID],
           mbQuery2Vecs: Query2Vecs.typ,
           mbSearchUserId0: Option[UUID],
           labels: Set[String],
           mbBegin: Option[TimeStamp],
           mbEnd: Option[TimeStamp],
           logLevel: Option[Level])
          (implicit markDao: MarkDao,
           userDao: UserDao,
           idfModel: IDFModel,
           ec: ExecutionContext): Future[Data[Mark]] = {

    // set logging level for this MarksStream *instance* (change prefix w/ "I" to prevent modifying the other logger)
    // "Note that you can also tell logback to periodically scan your config file"
    // https://stackoverflow.com/questions/3837801/how-to-change-root-logging-level-programmatically
    val loggerI: Logger = {
      val logback = LoggerFactory.getLogger("I" + classOf[MarksStream].getName).asInstanceOf[LogbackLogger]
      logLevel.filter(_ != logback.getLevel).foreach { lv => logback.setLevel(lv); logback.debug(s"Overriding log level to: $lv") }
      new Logger(logback)
    }

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = mbQuery2Vecs.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val mbSearchTermVecs = mbQuery2Vecs.map(_._2)

    // if the search & calling users are the same then only show MarkRefs in the search results if query words or labels
    // exist (o/w we're simply *listing* the calling user's marks, perhaps with begin/end args as profileDots does; this
    // cannot be tested in FacetTests because the alternate listing impl is in hamstoo/MarksController.list)
    val mbSearchUserId1 = mbSearchUserId0.orElse(mbCallingUserId)
    val hasQuery = mbQuery2Vecs.nonEmpty || labels.nonEmpty // e.g. if searching for marks with "SharedWithMe" label
    //val includeMarkRefs = mbSearchUserId1 != mbCallingUserId || hasQuery // old buggy impl (issue #339)
    val includeMarkRefs = mbSearchUserId1 == mbCallingUserId && hasQuery
    logger.info(s"includeMarkRefs:$includeMarkRefs = usersEqual:${mbSearchUserId1 == mbCallingUserId} && hasQuery:$hasQuery")

    def logMarks(whence: String)(ms: Traversable[Mark]): Traversable[Mark] =
      ms.map { m => loggerI.trace(s"$whence: ${m.id}/${m.markRef.map(_.markId)}"); m }

    // perform filterNot rather than relying on set union because scoredMs will have not only `score` field
    // populated but could also have different labels/rating due to MarkRef masking
    def union(set0: Set[Mark], set1: Set[Mark]): Set[Mark] = set0 ++ set1.filterNot(c => set0.exists(_.id == c.id))

    // get a couple of queries off-and-running before we start Future-flatMap-chaining

    // Mongo Text Index search (e.g. includes stemming) over `entries` collection (and filter results by labels)
    val fscoredMs = mbQuerySeq.mapOrEmptyFuture { w =>
                      markDao.search(mbSearchUserId1.toSet, w, begin = mbBegin, end = mbEnd)
                        .map(_.toSeq.filter(m => m.markRef.isEmpty && m.hasTags(labels)))
                        .flatMap(filterAuthorizedRead(_, mbCallingUserId))
                        .map(logMarks("fscoredMs"))
                    }

    // every single non-ref mark (refs are handled below, and included only if they match search terms)
    val funscoredMs = mbSearchUserId1.fold(Future.successful(Seq.empty[Mark])) { id =>
                        markDao.retrieve(id, tags = labels, begin = mbBegin, end = mbEnd)
                          .map(_.filter(_.markRef.isEmpty))
                          .flatMap(filterAuthorizedRead(_, mbCallingUserId))
                          .map(logMarks("funscoredMs")(_).toSeq)
                      }

    for {
      // MarkRefs (i.e. marks that aren't owned by the calling user)
      id2Ref <- if (!includeMarkRefs || mbCallingUserId.isEmpty) Future.successful(Map.empty[ObjectId, MarkRef])
                else markDao.retrieveRefed(mbCallingUserId.get, begin = mbBegin, end = mbEnd)
      candidateRefs <- markDao.retrieveInsecureSeq(id2Ref.keys.toSeq, begin = mbBegin, end = mbEnd)
                         .map(_.maskAndFilterTags(labels, id2Ref, mbCallingUserId))
                         .map(logMarks("candidateRefs"))

      // if the search/calling users are different, then only include calling user's MarkRefs that refer to search
      // user's marks (i.e. exclude marks that were shared _to_ the search user) because they're owned by others with
      // (probably) no connection to the calling user; but if the search/calling user are the same, then include
      // all of calling user's MarkRefs
      refUserIds = if (mbSearchUserId1 != mbCallingUserId) mbSearchUserId1.toSet else candidateRefs.map(_.userId).toSet
      refMarkIds = id2Ref.keySet

      // perform MongoDB Text Index search over referenced marks (i.e. marks owned by other users) and then impose
      // any rating or label changes this user has made on top of those references
      fscoredRefs = mbQuerySeq.mapOrEmptyFuture { w =>
        markDao.search(refUserIds, w, ids = Some(refMarkIds))
          .map(_.maskAndFilterTags(labels, id2Ref, mbCallingUserId))
      }

      // "candidates" are ALL of the marks viewable to the callingUser (with the appropriate labels), which will
      // include even those that were not returned by MongoDB Text Index search
      unscoredMs <- funscoredMs.map(ms => union(candidateRefs.toSet, ms.toSet))
        .map(logMarks("unscoredMs"))

      sms <- fscoredMs; srefs <- fscoredRefs
      scoredUngroupedMs = sms.zip(srefs).map { case (ms, refs) => ms ++ refs }
        .map(logMarks("scoredUngroupedMs"))

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

      val entries = union(scoredMs.toSet, unscoredMs.toSet)

      entries.toSeq.map(_.id).groupBy(identity).mapValues(_.size).filter(_._2 > 1)
        .foreach { kv => logger.error(s"Mark ${kv._1} occurs ${kv._2} times") }

      logMarks("entries")(entries)
        .map(m => Datum(m, MarkId(m.id), m.timeFrom))
        .to[immutable.Seq]
    }
  }

  // allows marks by one user (the search user) to be searched by another user (the calling user)
  case class SearchUserIdOptional() extends OptionalInjectId[Option[UUID]]("search.user.id", None)

  // allows marks search to filter for specific labels
  case class SearchLabelsOptional() extends OptionalInjectId[Set[String]]("labels", Set.empty[String])

  /**
    * Filter for marks that the calling user is authorized to read.  This function relies on the marks being in
    * a linear Seq, so don't think you can just change it to be an Iterable--i.e. a Set won't work!
    */
  def filterAuthorizedRead(ms: Seq[Mark], mbCallingUserId: Option[UUID])
                          (implicit userDao: UserDao, ec: ExecutionContext): Future[Seq[Mark]] =
    Future.sequence(ms.map { _.isAuthorizedRead(mbCallingUserId.flatMap(User.apply))(userDao, implicitly) })
      .map { auths =>
        val filtered = ms.zip(auths).filter(_._2).map(_._1)
        if (ms.size > filtered.size) logger.debug(s"Filtered ${ms.size} marks down to ${filtered.size}")
        filtered
      }

  /**
    * Referenced marks need to have their labels unioned with those of their MarkRefs before they can be filtered.
    * Non-referenced marks can just pass directly to the label filtering stage untouched.
    */
  implicit class ExtendedMarkIter(private val marks: Iterable[Mark]) extends AnyVal {

    def maskAndFilterTags(tags: Set[String],
                          id2Ref: Map[ObjectId, MarkRef],
                          callingUserId: Option[UUID]): Iterable[Mark] = {

      def maskOrElse(m: Mark): Mark = m.mask(id2Ref.get(m.id), callingUserId)

      marks.map(maskOrElse).filter(_.hasTags(tags))
    }
  }
}
