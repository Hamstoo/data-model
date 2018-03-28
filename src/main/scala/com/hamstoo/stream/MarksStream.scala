package com.hamstoo.stream

import java.util.UUID

import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
import com.hamstoo.models._
import com.hamstoo.services.IDFModel
import com.hamstoo.services.VectorEmbeddingsService.{Query2VecsType, WordMass}
import com.hamstoo.stream.MarksStream.{Query2VecsOptional, SearchLabelsOptional}
import com.hamstoo.utils.{NON_IDS, ObjectId, TimeStamp}
import play.api.Logger

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  *
  */
@Singleton
class MarksStream @Inject() (@Named("calling.user.id") callingUserId: UUID,
                             @Named("search.user.id") searchUserId: UUID,
                             query2Vecs: Query2VecsOptional,
                             labels: SearchLabelsOptional)
                            (implicit marksDao: MongoMarksDao,
                             reprDao: MongoRepresentationDao,
                             idfModel: IDFModel)
    extends PreloadSource[MSearchable]((700 days).toMillis) {

  import MarksStream._

  val tags: Set[String] = labels.value

  /**  */
  override def preload(begin: TimeStamp, end: TimeStamp): Future[immutable.Iterable[Datum[MSearchable]]] = {

    // unpack query words/counts/vecs (which there may none of)
    val mbCleanedQuery = query2Vecs.value.map(_._1)
    val mbQuerySeq = mbCleanedQuery.map(_.map(_._1))
    val mbSearchTermVecs = query2Vecs.value.map(_._2)
//    val fsearchTermVecs = mbSearchTermVecs.getOrElse(Future.successful(Seq.empty[WordMass]))

    // get a couple of queries off-and-running before we start Future-flatMap-chaining

    // Mongo Text Index search (e.g. includes stemming) over `entries` collection (and filter results by labels)
    val fscoredMS = mbQuerySeq.mapOrEmptyFuture { w =>
      marksDao.search(Set(searchUserId), w) // TODO: begin/end
        .map(_.toSeq.filter(_.hasTags(tags))).flatMap(filterAuthorizedRead(_, callingUserId))
    }

    // every single mark with an existing representation
    val fcandidateMarks = marksDao.retrieveRepred(searchUserId, tags).flatMap(filterAuthorizedRead(_, callingUserId))

    for {
      // candidate referenced marks (i.e. marks that aren't owned by the calling user)
      id2Ref <- marksDao.retrieveRefed(callingUserId)
      candidateRefs <- marksDao.retrieveInsecureSeq(id2Ref.keys.toSeq) // TODO: begin/end
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
      candidateMarks <- fcandidateMarks.map(_ ++ candidateRefs)
      candidateReprIds = candidateMarks.map(_.primaryRepr).toSet
      usrContentReprIds = candidateMarks.flatMap(_.userContentRepr.filterNot(NON_IDS.contains)).toSet

      // run a separate MongoDB Text Index search over `representations` collection for each query word
//      fscoredReprs = mbQuerySeq.mapOrEmptyFuture(reprDao.search(candidateReprIds ++ usrContentReprIds, _))

      // also get any user-content reprs that might have been excluded by the above search (we can compute
      // similarities to these but we'll have to use the marks collection's Text Index score)
      // TODO: maybe use the representations collection's Text Index score and drop the marks collection's Text Index?
//      fusrContentReprs = reprDao.retrieve(usrContentReprIds)







  // TODO: next up: figure out how to make the selection/search of reprs in another stream dependent on this marks stream




      


      sms <- fscoredMS; srefs <- fscoredRefs
      scoredMarks = sms.zip(srefs).map { case (ms, refs) => ms ++ refs }

      // no remaining Futures depend on these, so might as well put them at the end
      /*searchTermVecs <- fsearchTermVecs; scoredReprs <- fscoredReprs; usrContentReprs <- fusrContentReprs*/

    } yield {
/*      logger.debug(s"Found ${sms.map(_.size).sum} scored marks, ${srefs.map(_.size).sum} scored refs, ${scoredMarks.map(_.size).sum} total")
      logger.debug(s"Found ${scoredReprs.size} scored reprs and ${usrContentReprs.size} user-content reprs")
      logger.debug(s"Found ${searchTermVecs.size} search term vectors")
*/
      // Optional vector for search query
      /*val opQVec: Option[Vec] = ops.flatten match {
        case seq if seq.size > 1 => Some(seq.tail.fold(seq.head) { (a, b) => for {i <- a.indices} yield a(i) + b(i) })
        case seq if seq.size == 1 => Some(seq.head)
        case _ => None
      }*/

      /** Weight `entries` collection search scores by both the number of repetitions of the query word and its IDF. */
      def wgtMkScore(qm: ((String, Int), MSearchable)): Double =
        qm._1._2 * idfModel.transform(qm._1._1) * qm._2.score.get // #reps * idf * score

      // `scoredMarks` will contain duplicate marks with different scores for different search terms so join them,
      // start by broadcasting each query word (qw) in `cleanedQuery` to its respective marks (qms)
      val groupedMs = mbCleanedQuery.fold(Iterable.empty[MSearchable]) { cleanedQuery =>
        cleanedQuery.view.zip(scoredMarks).flatMap { case (qw, qms) => qms.map((qw, _)) }
          .groupBy(_._2.id).values // group query words by mark ID
          .map { seqvw => seqvw.head._2.xcopy(score = Some(seqvw.map(wgtMkScore).sum)) }
      }

      // performing filterNot rather than relying on set union because groupedMs will have not only score
      // populated but could also have different labels/rating due to MarkRef masking
      val entries = groupedMs.toSet ++ candidateMarks.filterNot(c => groupedMs.exists(_.id == c.id))
      entries.map(m => Datum(MarkId(m.id), m.timeFrom, m))

      // TODO: join w/ repr and user-content repr for each mark
    }
  }
}

object MarksStream {

  val logger = Logger(classOf[MarksStream])
  //val logger = Logger(MarksStream.getClass)

  // https://github.com/google/guice/wiki/FrequentlyAskedQuestions#how-can-i-inject-optional-parameters-into-a-constructor
  class Query2VecsOptional {
    @Inject(optional = true) @Named("query2Vecs")
    val value: Option[Query2VecsType] = None
  }

  class SearchLabelsOptional {
    @Inject(optional = true) @Named("labels")
    val value: Set[String] = Set.empty[String]
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

  /** We need to return a Future.successful(Seq.empty[T]) in a few different places if mbQuerySeq is None. */
  implicit class ExtendedQuerySeq(private val mbQuerySeq: Option[Seq[String]]) extends AnyVal {
    def mapOrEmptyFuture[T](f: String => Future[T]): Future[Seq[T]] =
      mbQuerySeq.fold(Future.successful(Seq.empty[T])) { querySeq => Future.sequence(querySeq.map(w => f(w))) }
  }
}
