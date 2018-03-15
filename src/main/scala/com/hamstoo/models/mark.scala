package com.hamstoo.models

import java.net.URL
import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.{ExpectedRating, MarkAux}
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.utils.{DurationMils, ExtendedString, INF_TIME, NON_IDS, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import org.apache.commons.text.StringEscapeUtils
import org.apache.commons.text.similarity.{CosineSimilarity, LevenshteinDistance}
import org.commonmark.node._
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.Logger
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.matching.Regex


/**
  * User content data model. This case class is also used for front-end JSON formatting.
  * The fields are:
  *
  * @param subj           - the rated element as a string of text; either a header of the marked page, or the rated
  *                       string itself
  * @param url            - an optional url
  * @param rating         - the value assigned to the mark by the user, from 0.0 to 5.0
  * @param tags           - a set of tags assigned to the mark by the user
  * @param comment        - an optional text comment assigned to the mark by the user
  * @param commentEncoded - markdown converted to HTML; set by class init
  */
case class MarkData(override val subj: String,
                    override val url: Option[String],
                    override val rating: Option[Double] = None,
                    override val tags: Option[Set[String]] = None,
                    override val comment: Option[String] = None,
                    var commentEncoded: Option[String] = None)
    extends MDSearchable(subj, url, rating, tags, comment) {

  import MarkData._

  // populate `commentEncoded` by deriving it from the value of `comment`
  commentEncoded = comment.map { c: String => // example: <IMG SRC=JaVaScRiPt:alert('XSS')>

    // example: <p>&lt;IMG SRC=JaVaScRiPt:alert('XSS')&gt;</p>
    // https://github.com/atlassian/commonmark-java
    // as well parses and renders markdown markup language
    val document: Node = parser.parse(c)

    // detects embedded links in text only and make them clickable (issue #136)
    // ignores html links (anchors) to avoid double tags because
    // commonmark.parser.parse(...) does not allocate <a>
    // (anchor tag) from text as a separate node
    // and such tags will be double tagged like <a href...><a href...>Link</a></a>
    val visitor = new TextNodesVisitor
    document.accept(visitor)

    val html = renderer.render(document)

    // example: <p><IMG SRC=JaVaScRiPt:alert('XSS')></p>
    // convert that &ldquo; back to a < character
    val html2 = StringEscapeUtils.unescapeHtml4(html)

    // example: <p><img></p>
    Jsoup.clean(html2, htmlTagsWhitelist)
  }

  override def cleanUrl: MarkData = this.url match {
    case Some(u) => this.copy(url = Some(cleanUrl(u)))
    case _ => this
  }

  /** Check for `Automarked` label. */
  def isAutomarked: Boolean = tags.exists(_.exists(_.equalsIgnoreCase(MarkData.AUTOSAVE_TAG)))

  /**
    * Merge two `MarkData`s with as little data loss as possible.  Not using `copy` here to ensure that if
    * additional fields are added to the constructor they aren't forgotten here.
    */
  def merge(oth: MarkData): MarkData = {

    if (subj != oth.subj)
      logger.warn(s"Merging two marks with different subjects '$subj' and '${oth.subj}'; ignoring latter")
    if (url.isDefined && oth.url.isDefined && url.get != oth.url.get)
      logger.warn(s"Merging two marks with different URLs ${url.get} and ${oth.url.get}; ignoring latter")

    MarkData(if (subj.length >= oth.subj.length) subj else oth.subj,
             url.orElse(oth.url),
             oth.rating.orElse(rating), // use new rating in case user's intent was to rate smth differently
             Some(tags.getOrElse(Nil.toSet) union oth.tags.getOrElse(Nil.toSet)),
             comment.map(_ + oth.comment.map(commentMergeSeparator + _).getOrElse("")).orElse(oth.comment)
    )
  }

  /** If the two MarkDatas are equal, as far as generating a public representation would be concerned. */
  // TODO: the interface for constructing a public repr should only be allowed to include these fields via having its own class
  def equalsPerPubRepr(other: MarkData): Boolean =
    url.isDefined && url == other.url || url.isEmpty && subj == other.subj

  /** If the two MarkDatas are equal, as far as generating a user representation would be concerned. */
  // TODO: the interface for constructing a user repr should only be allowed to include these fields via having its own class
  def equalsPerUserRepr(other: MarkData): Boolean =
    copy(url = None, rating = None) == other.copy(url = None, rating = None)
}

object MarkData {
  val logger: Logger = Logger(classOf[MarkData])

  /* attention: mutable Java classes below.
  for markdown parsing/rendering: */
  lazy val parser: Parser = Parser.builder().build()
  lazy val renderer: HtmlRenderer = HtmlRenderer.builder().build()

  // for XSS filtering (https://jsoup.org/cookbook/cleaning-html/whitelist-sanitizer)
  lazy val htmlTagsWhitelist: Whitelist = Whitelist.relaxed()
    .addTags("hr") // horizontal rule
    .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer") /*
    https://medium.com/@jitbit/target-blank-the-most-underestimated-vulnerability-ever-96e328301f4c : */
    .addEnforcedAttribute("a", "target", "_blank")

  val commentMergeSeparator: String = "\n\n---\n\n"

  // this tag is present in calls to backend's MarksController.saveMark when browser extension automark feature is on,
  // this value must match the value in chrome-extension's timeTracker.js
  val AUTOSAVE_TAG = "Automarked"
  val IMPORT_TAG = "Imported"
  val UPLOAD_TAG = "Uploaded"
  val SHARED_WITH_ME_TAG = "SharedWithMe"
}

/**
  * Non-owners of marks may want to add their own ratings and/or labels.  This class allows them to do so.
  * @param markId  A reference to another user's mark.
  * @param rating  This user's rating.
  * @param tags    This user's additional tags (in addition to those of the owner user) which could include
  *                SHARED_WITH_ME_TAG initially (the first time this non-owner user visits the shared mark) by default.
  */
case class MarkRef(markId: ObjectId,
                   rating: Option[Double] = None,
                   tags: Option[Set[String]] = None)

/**
  * This class is used to detect embedded links in text and wrap them w/ <a> anchor tags.  It visits
  * only text nodes.
  */
class TextNodesVisitor extends AbstractVisitor {
  import TextNodesVisitor._

  override def visit(text: Text): Unit = {
    // find and wrap links
    val wrappedEmbeddedLinks = embeddedLinksToHtmlLinks(text.getLiteral)
    // apply changes to currently visiting text node
    text.setLiteral(wrappedEmbeddedLinks)
  }
}

object TextNodesVisitor {

  /**
    * Find all embedded URLs and convert them to HTML <a> links (anchors). This regex is designed to ignore HTML link
    * tags. It doesn't need to ignore markdown links because those have already been processed into HTML at this point.
    *
    * 1st regex part is (?<!href="), it checks that found link should not be prepended by href=" expression,
    *   i.e. take if 2nd regex part is "not prepended by" 1st part.
    * 2nd regex part which follows after (?<!href=") is looking for urls format
    *   1st part of 2nd regex part ((?:https?|ftp)://) checks protocol
    * (?<!href=") - this ignore condition should stay because commonmark.parser.parse(...) does not allocate <a>
    *   (anchor tag) from text as a separate node
    */
  def embeddedLinksToHtmlLinks(text: String): String = {
    val regexStr =
      "(?<!href=\")" + // ignore http pattern prepended by 'href=' expression
        "((?:https?|ftp)://)" + // check protocol
        "(([a-zA-Z0-‌​9\\-\\._\\?\\,\\'/\\+&am‌​p;%\\$#\\=~])*[^\\.\\,\\)\\(\\s])" // allowed anything which is allowed in url
    val ignoreTagsAndFindLinksInText: Regex = regexStr.r
    ignoreTagsAndFindLinksInText.replaceAllIn(text, m => "<a href=\"" + m.group(0) + "\">" + m.group(0) + "</a>")
  }
}

/**
  * Object that represents different possible mark states.
  * @param reprId     Representation ID
  * @param reprType   Representation type, database requires a String (also see `object ReprType extends Enumeration`)
  * @param expRating  Expected rating generated by repr-engine.ExpectedRatingActor
  */
case class ReprInfo(reprId: ObjectId,
                    reprType: String,
                    created: TimeStamp = TIME_NOW,
                    expRating: Option[ObjectId] = None) {

  /** Check if expected rating has not yet been computed for this Representation. */
  def eratable: Boolean = expRating.isEmpty

  def isPublic: Boolean = ReprType.withName(reprType) == ReprType.PUBLIC
  def isPrivate: Boolean = ReprType.withName(reprType) == ReprType.PRIVATE
  def isUserContent: Boolean = ReprType.withName(reprType) == ReprType.USER_CONTENT
}

/**
  * User history (list) entry data model. An `Entry` is a `Mark` that belongs to a
  * particular user along with an ID and timestamp.
  *
  * @param userId   - owner's user ID
  * @param sharedWith - defines which other users are allowed to read or write this Mark[Data]
  * @param id       - the mark's alphanumerical string, used as an identifier common with all the marks versions
  * @param mark     - user-provided content
  * @param markRef - Content that references (and masks) another mark that is owned by a different user.
  *                 When this field is provided, the `mark` field should be empty.
  *                 TODO: What if this non-owner goes and marks the same URL?  Just create another mark!
  * @param aux      - additional fields holding satellite data
  * @param urlPrfx  - binary prefix of `mark.url` for the purpose of indexing by mongodb; set by class init
  *                   Binary prefix is used as filtering and 1st stage of urls equality estimation
  *                   https://en.wikipedia.org/wiki/Binary_prefix
  * @param reprs    - A history of different mark states/views/representations as marked by the user
  * @param timeFrom - timestamp of last edit
  * @param timeThru - the moment of time until which this version is latest
  * @param mergeId  - if this mark was merged into another, this will be the ID of that other
  *
  * @param score    - `score` is not part of the documents in the database, but it is returned from
  *                 `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(override val userId: UUID,
                override val id: ObjectId = generateDbId(Mark.ID_LENGTH),
                override val mark: MarkData,
                override val markRef: Option[MarkRef] = None,
                override val aux: Option[MarkAux] = Some(MarkAux(None, None)),
                var urlPrfx: Option[mutable.WrappedArray[Byte]] = None, // using *hashable* WrappedArray here
                override val reprs: Seq[ReprInfo] = Nil,
                override val timeFrom: TimeStamp = TIME_NOW,
                override val timeThru: TimeStamp = INF_TIME,
                override val modifiedBy: Option[UUID] = None,
                mergeId: Option[String] = None,
                override val sharedWith: Option[SharedWith] = None,
                override val nSharedFrom: Option[Int] = Some(0),
                override val nSharedTo: Option[Int] = Some(0),
                override val score: Option[Double] = None)
    extends MSearchable(userId, id, mark, markRef, aux, reprs, timeFrom, timeThru,
                        modifiedBy, sharedWith, nSharedFrom, nSharedTo, score) {

  urlPrfx = mark.url map (_.binaryPrefix)

  import Mark._

  /** A mark has representable user content if it doesn't have a USER_CONTENT repr. */
  def representableUserContent: Boolean = !reprs.exists(_.isUserContent)

  /** Get a private representation without an expected rating, if it exists. */
  def unratedPrivRepr: Option[ObjectId] = reprs.find(ri => ri.eratable && ri.isPrivate).map(_.reprId)

  /**
    * Return true if the mark is (potentially) representable but not yet represented.  In the case of public
    * representations, even if the mark doesn't have a URL, we'll still try to derive a representation from the subject
    * in case LinkageService.digest timed out when originally trying to generate a title for the mark (issue #195).
    */
  def eratablePublic: Boolean = reprs.exists(ri => ri.eratable && ri.isPublic)
  def eratablePrivate: Boolean = unratedPrivRepr.nonEmpty
  def eratableUserContent: Boolean = reprs.exists(ri => ri.eratable && ri.isUserContent)

  /** Return true if the mark is current (i.e. hasn't been updated or deleted). */
  def isCurrent: Boolean = timeThru == INF_TIME

  /**
    * Mask a Mark's MarkData with a MarkRef--for the viewing pleasure of a shared-with, non-owner of the Mark.
    * Returns the mark owner's rating in the `ownerRating` field of the returned MarkData.  Note that if
    * `callingUser` owns the mark, the supplied optRef is completely ignored.
    */
  override def mask(optRef: Option[MarkRef], callingUser: Option[User]): Mark = {
    if (callingUser.exists(ownedBy)) this else {

      // still mask even if optRef is None to ensure that the owner's rating is moved to the `ownerRating` field
      val ref = optRef.getOrElse(MarkRef(id)) // create an empty MarkRef (id isn't really used)

      val unionedTags = mark.tags.getOrElse(Set.empty[String]) ++ ref.tags.getOrElse(Set.empty[String])
      val mdata = mark.copy(rating = ref.rating,
                            tags = if (unionedTags.isEmpty) None else Some(unionedTags))
      mdata.bMasked = true // even though mdata is a val we are still allowed to do this--huh!?!
      mdata.ownerRating = mark.rating
      copy(mark = mdata)
    }
  }

  /**
    * Merge two marks.  This method is called from the `repr-engine` when a new mark's, `oth`, representations are
    * similar enough (according to `Representation.isDuplicate`) to an existing mark's, `this`.  So `oth` probably
    * won't have it's reprs set--unless one of them was set and the other not.
    */
  def merge(oth: Mark): Mark = {
    assert(this.userId == oth.userId)

    if (reprs.nonEmpty && oth.reprs.nonEmpty && reprs != oth.reprs)
      logger.warn(s"Merging two marks, $id and ${oth.id}, with different private representations; ignoring latter")

    // TODO: how do we ensure that additional fields added to the constructor are accounted for here?
    // TODO: how do we ensure that other data (like highlights) that reference markIds are accounted for?
    copy(
      mark = mark.merge(oth.mark),
      aux = if (oth.aux.isDefined) aux.map(_.merge(oth.aux.get)).orElse(oth.aux) else aux,
      reprs = reprs ++ oth.reprs.filter(_.isPrivate) // must be consistent w/ MongoPagesDao.mergePrivatePages
    )
  }

  /**
    * If a Mark's content has changed sufficiently to necessitate a recalculation of its reprs, then remove them.
    * In other words, it can keep its reprs if whatever it has become is no different from what it was.
    */
  def removeStaleReprs(original: Mark): Mark = {

    // if the URL has changed then discard the old public repr (only the public one though as the private one is
    // based on private user content that was only available from the browser extension at the time the user first
    // created it)
    val reprs0 = if (mark equalsPerPubRepr original.mark) reprs else reprs.filterNot(_.isPublic)

    // if user-generated content has changed then discard the old user repr (also see unsetUserContentReprId below)
    val reprs1 = if (mark equalsPerUserRepr original.mark) reprs0 else reprs0.filterNot(_.isUserContent)

    copy(reprs = reprs1)
  }

  /**
    * Fairly standard equals definition.  Required b/c of the overriding of hashCode.
    * TODO: can this be removed now that we're no longer relying on it for set-union in backend's SearchService.search?
    */
  override def equals(other: Any): Boolean = other match {
    case other: Mark => other.canEqual(this) && this.hashCode == other.hashCode
    case _ => false
  }

  /** Same as `equals` except ignoring timeFrom/timeThru. */
  def equalsIgnoreTimeStamps(other: Mark): Boolean =
    equals(other.copy(timeFrom = timeFrom, timeThru = timeThru, score = score))

  /**
    * Avoid incorporating `score: Option[Double]` into the hash code. `Product` does not define its own `hashCode` so
    * `super.hashCode` comes from `Any` and so the implementation of `hashCode` that is automatically generated for
    * case classes has to be copy and pasted here.  More at the following link:
    * https://stackoverflow.com/questions/5866720/hashcode-in-case-classes-in-scala
    * And an explanation here: https://stackoverflow.com/a/44708937/2030627
    */
  override def hashCode: Int = this.score match {
    case None => scala.runtime.ScalaRunTime._hashCode(this)
    case Some(_) => this.copy(score = None).hashCode
  }
}

/**
  * Searchable MarkData includes all fields excetp `commentEncoded`.
  * TODO: Would it be better to just put `commentEncoded` and the 2 MarkAux fields in MSearchable?
  */
class MDSearchable(val subj: String,
                   val url: Option[String],
                   val rating: Option[Double],
                   val tags: Option[Set[String]],
                   val comment: Option[String]) {

  // when a Mark gets masked by a MarkRef, bMasked will be set to true and ownerRating will be set to the original
  // rating value (not part of the data(base) model)
  var bMasked: Boolean = false
  var ownerRating: Option[Double] = None

  def xcopy(subj: String = subj,
            url: Option[String] = url,
            rating: Option[Double] = rating,
            tags: Option[Set[String]] = tags,
            comment: Option[String] = comment) =
    new MDSearchable(subj, url, rating, tags, comment)

  /** Method for cleaning urls */
  @tailrec
  final def cleanUrl(url: String): String = {
    if (url.contains("#")) cleanUrl(url.split('#').head) // TODO: is this reasonable?
    //else if (url.contains("?")) cleanUrl(url.split('?').head) // TODO: is this reasonable?
    else url
  }

  /** Clean url from query parameters and fragment identifier */
  def cleanUrl: MDSearchable = this.url match {
    case Some(u) => this.xcopy(url = Some(cleanUrl(u)))
    case _ => this
  }

  /**
    * This method is called `xtoString` instead of `toString` to avoid conflict with `case class Mark` which
    * is going to generate its own `toString` method.  Also see `xcopy`.
    */
  def xtoString: String =
    s"${classOf[MDSearchable].getSimpleName}($subj,$url,$rating,$tags,$comment)"
}

object MDSearchable {

  def apply(subj: String,
            url: Option[String],
            rating: Option[Double],
            tags: Option[Set[String]],
            comment: Option[String]): MDSearchable =
    new MDSearchable(subj, url, rating, tags, comment)

  def unapply(obj: MDSearchable):
      Option[(String, Option[String], Option[Double], Option[Set[String]], Option[String])] =
    Some((obj.subj, obj.url, obj.rating, obj.tags, obj.comment))
}

/**
  * This class lists all of the fields required by the backend's `SearchService`.  `SearchService` loads and processes
  * so many marks at once that we can't load the full `Mark` data structure for every one of them, though the severity
  * of this issue is likely much lessened by the fact that we no longer place `Page`s on `Mark`s.  Also see the
  * analogous `RSearchable` and `MDSearchable` classes.
  */
class MSearchable(val userId: UUID,
                  val id: ObjectId,
                  val mark: MDSearchable,
                  val markRef: Option[MarkRef],
                  val aux: Option[MarkAux],
                  val reprs: Seq[ReprInfo],
                  val timeFrom: TimeStamp,
                  val timeThru: TimeStamp,
                  val modifiedBy: Option[UUID],
                  val sharedWith: Option[SharedWith],
                  val nSharedFrom: Option[Int],
                  val nSharedTo: Option[Int],
                  val score: Option[Double]) extends Shareable {

  /**
    * This method is called `xcopy` instead of `copy` to avoid conflict with `case class Mark` which
    * is going to generate its own `copy` method.  Also see `xtoString`.
    */
  def xcopy(userId: UUID = userId,
            id: ObjectId = id,
            mark: MDSearchable = mark,
            markRef: Option[MarkRef] = markRef,
            aux: Option[MarkAux] = aux,
            reprs: Seq[ReprInfo] = reprs,
            timeFrom: TimeStamp = timeFrom,
            timeThru: TimeStamp = timeThru,
            modifiedBy: Option[UUID] = modifiedBy,
            sharedWith: Option[SharedWith] = sharedWith,
            nSharedFrom: Option[Int] = nSharedFrom,
            nSharedTo: Option[Int] = nSharedTo,
            score: Option[Double] = score): MSearchable =
    new MSearchable(userId, id, mark, markRef, aux, reprs, timeFrom, timeThru,
                    modifiedBy, sharedWith, nSharedFrom, nSharedTo, score)

  /**
    * Returning an Option[String] would be more "correct" here, but returning the empty string just makes things a
    * lot cleaner on the other end.  However alas, note not doing so for `expectedRating`.
    */
  def primaryRepr: ObjectId  = privRepr.orElse(pubRepr).getOrElse("")

  /** Basically the same impl as `primaryRepr` except that it returns an expected rating ID (as an Option). */
  def expectedRating: Option[ObjectId] = {
    val privExpRating = reprs
      .filter(x => x.isPrivate && !NON_IDS.contains(x.expRating.getOrElse("")))
      .sortBy(_.created).lastOption.flatMap(_.expRating)
    lazy val pubExpRating = reprs.find(_.isPublic).flatMap(_.expRating)
    privExpRating.orElse(pubExpRating)
  }

  /** Returns true if this Mark's MarkData has all of the test tags. */
  def hasTags(testTags: Set[String]): Boolean = testTags.forall(t => mark.tags.exists(_.contains(t)))

  /**
    * Return latest representation ID, if it exists.  Even though public and user-content reprs are supposed to
    * be singletons, it doesn't hurt to be conservative and return the most recent rather than just using `find`.
    */
  def privRepr: Option[ObjectId] = repr(x => x.isPrivate && !NON_IDS.contains(x.reprId))
  def pubRepr: Option[ObjectId] = repr(_.isPublic)
  def userContentRepr: Option[ObjectId] = repr(_.isUserContent)
  private def repr(pred: ReprInfo => Boolean): Option[ObjectId] =
    reprs.filter(pred).sortBy(_.created).lastOption.map(_.reprId)

  /**
    * Mask a Mark's MarkData with a MarkRef--for the viewing pleasure of a shared-with, non-owner of the Mark.
    * Returns the mark owner's rating in the `ownerRating` field of the returned MarkData.  Note that if
    * `callingUser` owns the mark, the supplied optRef is completely ignored.
    */
  def mask(optRef: Option[MarkRef], callingUser: Option[User]): MSearchable = {
    if (callingUser.exists(ownedBy)) this else {

      // still mask even if optRef is None to ensure that the owner's rating is moved to the `ownerRating` field
      val ref = optRef.getOrElse(MarkRef(id)) // create an empty MarkRef (id isn't really used)

      val unionedTags = mark.tags.getOrElse(Set.empty[String]) ++ ref.tags.getOrElse(Set.empty[String])
      val mdata = mark.xcopy(rating = ref.rating,
        tags = if (unionedTags.isEmpty) None else Some(unionedTags))
      mdata.bMasked = true // even though mdata is a val we are still allowed to do this--huh!?!
      mdata.ownerRating = mark.rating
      xcopy(mark = mdata)
    }
  }

  /**
    * If the mark has been masked, show the original rating in blue, o/w lookup the mark's expected rating ID in
    * the given `eratings` map and show that in blue.  This allows us to (1) always show the current user's rating
    * in orange, even when displaying another shared-from user's mark, and (2) hide shared-from users' rating
    * predictions, which contain information about more than just the mark being shared, from shared-to users.
    */
  def blueRating(eratings: Map[ObjectId, ExpectedRating]): Option[Double] =
    if (mark.bMasked) mark.ownerRating else expectedRating.flatMap(eratings.get).flatMap(_.value)

  /**
    * This method is called `xtoString` instead of `toString` to avoid conflict with `case class Mark` which
    * is going to generate its own `toString` method.  Also see `xcopy`.
    */
  def xtoString: String =
    s"${classOf[MSearchable].getSimpleName}($userId,$id,${mark.xtoString},$markRef,$aux,$reprs,$timeFrom,$timeThru,$modifiedBy,$sharedWith,$nSharedFrom,$nSharedTo,$score)"
}

object MSearchable {
  def apply(userId: UUID,
            id: ObjectId,
            mark: MDSearchable,
            markRef: Option[MarkRef],
            aux: Option[MarkAux],
            reprs: Seq[ReprInfo],
            timeFrom: TimeStamp,
            timeThru: TimeStamp,
            modifiedBy: Option[UUID],
            sharedWith: Option[SharedWith],
            nSharedFrom: Option[Int],
            nSharedTo: Option[Int],
            score: Option[Double]): MSearchable =
    new MSearchable(userId, id, mark, markRef, aux, reprs, timeFrom, timeThru,
                    modifiedBy, sharedWith, nSharedFrom, nSharedTo, score)

  def unapply(arg: MSearchable):
      Option[(UUID, String, MDSearchable, Option[MarkRef], Option[MarkAux], Seq[ReprInfo], TimeStamp, TimeStamp,
             Option[UUID], Option[SharedWith], Option[Int], Option[Int], Option[Double])] =
    Some((arg.userId, arg.id, arg.mark, arg.markRef, arg.aux, arg.reprs, arg.timeFrom, arg.timeThru,
          arg.modifiedBy, arg.sharedWith, arg.nSharedFrom, arg.nSharedTo,arg.score))
}

object Mark extends BSONHandlers {
  val logger: Logger = Logger(classOf[Mark])

  // probably a good idea to log this somewhere, and this seems like a good place for it to only happen once
  logger.info("data-model version " + Option(getClass.getPackage.getImplementationVersion).getOrElse("null"))

  case class RangeMils(begin: TimeStamp, end: TimeStamp)

  /**
    * Auxiliary stats pertaining to a `Mark`.
    *
    * The two `total` vars will only be computed if their respective `tab` vals are non-None so that the latter can
    * be removed (see `cleanRanges`) and the former preserved to reduce the memory footprint of MarkAux instances.
    */
  case class MarkAux(tabVisible: Option[Seq[RangeMils]],
                     tabBground: Option[Seq[RangeMils]],
                     var totalVisible: Option[DurationMils] = None,
                     var totalBground: Option[DurationMils] = None) {

    /** Not using `copy` in this merge to ensure if new fields are added, they aren't forgotten here. */
    def merge(oth: MarkAux) =
      MarkAux(Some(tabVisible.getOrElse(Nil) ++ oth.tabVisible.getOrElse(Nil)),
              Some(tabBground.getOrElse(Nil) ++ oth.tabBground.getOrElse(Nil)))

    /** These methods return the total aggregated amount of time in the respective sequences of ranges. */
    if (tabVisible.nonEmpty) totalVisible = Some(total(tabVisible))
    if (tabBground.nonEmpty) totalBground = Some(total(tabBground))
    def total(tabSomething: Option[Seq[RangeMils]]): DurationMils =
      tabSomething.map(_.foldLeft(0L)((agg, range) => agg + range.end - range.begin)).getOrElse(0L)

    /** Returns a copy with the two sequences of RangeMils removed--to preserve memory. */
    def cleanRanges: MarkAux = this.copy(tabVisible = None, tabBground = None)
  }

  /**
    * Expected rating for a mark including the number of other marks that went into generating it and when
    * it was generated (and how long it was "active" for).
    */
  case class ExpectedRating(id: String = generateDbId(Mark.ID_LENGTH),
                            value: Option[Double],
                            n: Int,
                            similarReprs: Option[Seq[String]] = None,
                            timeFrom: Long = TIME_NOW,
                            timeThru: Long = INF_TIME) extends ReprEngineProduct[ExpectedRating] {

    override def withTimeFrom(timeFrom: Long): ExpectedRating = this.copy(timeFrom = timeFrom)
  }

  val ID_LENGTH: Int = 16

  val USR: String = nameOf[Mark](_.userId)
  val ID: String = Shareable.ID
  val MARK: String = nameOf[Mark](_.mark)
  val AUX: String = nameOf[Mark](_.aux)
  val URLPRFX: String = nameOf[Mark](_.urlPrfx)
  val TIMEFROM: String = nameOf[Mark](_.timeFrom)
  val TIMETHRU: String = nameOf[Mark](_.timeThru)
  val MERGEID: String = nameOf[Mark](_.mergeId)
  val REPRS: String = nameOf[Mark](_.reprs)

  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = nameOf[Mark](_.score)

  // the 'x' is for "extended" (changing these from non-x has already identified one bug)
  val SUBJx: String = MARK + "." + nameOf[MarkData](_.subj)
  val URLx: String = MARK + "." + nameOf[MarkData](_.url)
  val STARSx: String = MARK + "." + nameOf[MarkData](_.rating)
  val TAGSx: String = MARK + "." + nameOf[MarkData](_.tags)
  val COMNTx: String = MARK + "." + nameOf[MarkData](_.comment)
  val COMNTENCx: String = MARK + "." + nameOf[MarkData](_.commentEncoded)

  val REFIDx: String = nameOf[Mark](_.markRef) + "." + nameOf[MarkRef](_.markId)

  val TABVISx: String = AUX + "." + nameOf[MarkAux](_.tabVisible)
  val TABBGx: String = AUX + "." + nameOf[MarkAux](_.tabBground)

  // ReprInfo constants value (note that these are not 'x'/extended as they aren't prefixed w/ "mark.")
  val REPR_ID: String = nameOf[ReprInfo](_.reprId)
  val REPR_TYPE: String = nameOf[ReprInfo](_.reprType)
  val CREATED: String = nameOf[ReprInfo](_.created)
  val EXP_RATING: String = nameOf[ReprInfo](_.expRating)

  // the 'p' is for "position" update operator
  val REPR_IDx: String = REPRS + "." + REPR_ID
  val REPR_TYPEx: String = REPRS + "." + REPR_TYPE
  val CREATEDx: String = REPRS + "." + CREATED
  val EXP_RATINGx: String = REPRS + "." + EXP_RATING
  val EXP_RATINGxp: String = REPRS + ".$." + EXP_RATING

  val REPR_IDxp: String = REPRS + ".$." + REPR_ID
  val CREATEDxp: String = REPRS + ".$." + CREATED

  implicit val mDataData: BSONDocumentHandler[MDSearchable] = Macros.handler[MDSearchable]
  implicit val mSearchable: BSONDocumentHandler[MSearchable] = Macros.handler[MSearchable]
  val USRPRFX: String = nameOf[UrlDuplicate](_.userIdPrfx)
  assert(nameOf[UrlDuplicate](_.urlPrfx) == com.hamstoo.models.Mark.URLPRFX)
  assert(nameOf[UrlDuplicate](_.id) == com.hamstoo.models.Mark.ID)

  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val auxBsonHandler: BSONDocumentHandler[MarkAux] = Macros.handler[MarkAux]
  implicit val eratingBsonHandler: BSONDocumentHandler[ExpectedRating] = Macros.handler[ExpectedRating]
  implicit val markRefBsonHandler: BSONDocumentHandler[MarkRef] = Macros.handler[MarkRef]
  implicit val markDataBsonHandler: BSONDocumentHandler[MarkData] = Macros.handler[MarkData]
  implicit val reprRating: BSONDocumentHandler[ReprInfo] = Macros.handler[ReprInfo]
  implicit val entryBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]
  implicit val markDataJsonFormat: OFormat[MarkData] = Json.format[MarkData]
}
