package com.hamstoo.models

import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.services.TikaInstance
import com.hamstoo.utils.{ExtendedString, INF_TIME, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import org.apache.commons.text.StringEscapeUtils
import org.commonmark.node._
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.Logger
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, Macros}

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
  * @param pagePending    - don't let repr-engine process marks that are still waiting for their private pages
  * @param commentEncoded - markdown converted to HTML; set by class init
  */
case class MarkData(
                     subj: String,
                     url: Option[String],
                     rating: Option[Double] = None,
                     tags: Option[Set[String]] = None,
                     comment: Option[String] = None,
                     var commentEncoded: Option[String] = None,
                     pagePending: Option[Boolean] = None) {

  import MarkData._

  // when a Mark gets masked by a MarkRef, bMasked will be set to true and ownerRating will be set to the original
  // rating value (not part of the data(base) model)
  var bMasked: Boolean = false
  var ownerRating: Option[Double] = None

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
  def equalsPerPubRepr(other: MarkData): Boolean =
    url.isDefined && url == other.url || url.isEmpty && subj == other.subj

  /** If the two MarkDatas are equal, as far as generating a user representation would be concerned. */
  def equalsPerUserRepr(other: MarkData): Boolean = copy(url = None, rating = None, pagePending = None) ==
                                              other.copy(url = None, rating = None, pagePending = None)
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

/**
  * This is the data structure used to store external content, e.g. HTML files or PDFs.  It could be private content
  * downloaded via the chrome extension, the repr-engine downloads public content given a URL, or the file upload
  * process uploads content directly from the user's computer.
  */
case class Page(userId: UUID,
                id: String,
                reprType: String,
                mimeType: String,
                content: mutable.WrappedArray[Byte],
                created: Long = TIME_NOW)

object Page {

  /** A separate `apply` method that detects the MIME type automatically with Tika. */
  def apply(userId: UUID,
            id: String,
            reprType: String,
            content: mutable.WrappedArray[Byte]): Page = {
    val mimeType = TikaInstance.detect(content.toArray[Byte])
    Page(userId, id, reprType, mimeType, content)
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
  * Object that represent marks states
  * @param reprType - representation type
  * @param expRating - expected rating generated repr-engine.ExpectedRatingActor
  */
case class ReprInfo(reprId: String,
                    reprType: String,
                    expRating: Option[String] = None,
                    created: TimeStamp) {

  // check if object has not yet rated
  def eratablePrivate: Boolean = reprType == Representation.PRIVATE && expRating.isEmpty
  def eratablePublic: Boolean = reprType == Representation.PUBLIC && expRating.isEmpty
  def eratableUser: Boolean = reprType == Representation.USERS && expRating.isEmpty

  def isPublic: Boolean = reprType == Representation.PUBLIC
  def isPrivate: Boolean = reprType == Representation.PRIVATE
  def isUserContent: Boolean = reprType == Representation.USERS

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
//  * @param pages     - temporary holder for page sources, until a representation is constructed or assigned
//  * @param pubRepr  - optional public page representation id for this mark
//  * @param privRepr - optional personal user content representation id for this mark
//  * @param pubExpRating - expected rating of the mark given pubRepr and the user's rating history
  * @param timeFrom - timestamp of last edit
  * @param timeThru - the moment of time until which this version is latest
  * @param mergeId  - if this mark was merged into another, this will be the ID of that other
  *
  * @param score    - `score` is not part of the documents in the database, but it is returned from
  *                 `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(
                 userId: UUID,
                 id: ObjectId = generateDbId(Mark.ID_LENGTH),
                 mark: MarkData,
                 markRef: Option[MarkRef] = None,
                 aux: Option[MarkAux] = Some(MarkAux(None, None)),
                 var urlPrfx: Option[mutable.WrappedArray[Byte]] = None, // using *hashable* WrappedArray here
                 reprs: Seq[ReprInfo] = Nil,
                 pndPrivPages: Int = 0,
                 timeFrom: Long = TIME_NOW,
                 timeThru: Long = INF_TIME,
                 modifiedBy: Option[UUID] = None,
                 mergeId: Option[String] = None,
                 sharedWith: Option[SharedWith] = None,
                 nSharedFrom: Option[Int] = Some(0),
                 nSharedTo: Option[Int] = Some(0),
                 score: Option[Double] = None) extends Shareable {
  urlPrfx = mark.url map (_.binaryPrefix)

  import Mark._

  /**
    * Returning an Option[String] would be more "correct" here, but returning the empty string just makes things a
    * lot cleaner on the other end.  However alas, note not doing so for `expectedRating`.
    */
  def primaryRepr: String  = privRepr.orElse(pubRepr).getOrElse("")
  def expectedRating: Option[String] =
    reprs
    .filter(_.reprType == Representation.PRIVATE)
    .sortBy(_.created)
    .lastOption
    .flatMap(_.expRating) // expect rating from private representation
    .orElse(
      reprs
      .find(_.isPublic)
      .flatMap(_.expRating)
    )
  def representableUser: Boolean = !reprs.exists(_.reprType == Representation.USERS)

  /** Get a private representation without an expected rating, if it exists. */
  def unratedPrivRepr: Option[String] = {
    reprs
      .find(_.eratablePrivate)
      .map(_.reprId)
  }

  /** Return latest private representation id, if exist */
  def privRepr: Option[String] = {
    reprs
      .filter(_.reprType == Representation.PRIVATE)
      .sortBy(_.created)
      .lastOption
      .map(_.reprId)
  }

  /** Return public representation id, if exist */
  def pubRepr: Option[String] = {
    reprs
      .find(_.isPublic)
      .map(_.reprId)
  }

  /** Return user representation id, if exist */
  def userRepr: Option[String] = {
    reprs
      .find(_.isUserContent)
      .map(_.reprId)
  }

  /**
    * Return true if the mark is (potentially) representable but not yet represented.  In the case of public
    * representations, even if the mark doesn't have a URL, we'll still try to derive a representation from the subject
    * in case LinkageService.digest timed out when originally trying to generate a title for the mark (issue #195).
    */
  def eratablePublic: Boolean = reprs.exists(_.eratablePublic)
  def eratablePrivate: Boolean = reprs.exists(_.eratablePrivate)
  def eratableUser: Boolean = reprs.exists(_.eratableUser)

  /** Return true if the mark is current (i.e. hasn't been updated or deleted). */
  def isCurrent: Boolean = timeThru == INF_TIME

  /** Returns true if this Mark's MarkData has all of the test tags. */
  def hasTags(testTags: Set[String]): Boolean = testTags.forall(t => mark.tags.exists(_.contains(t)))

  /**
    * Mask a Mark's MarkData with a MarkRef--for the viewing pleasure of a shared-with, non-owner of the Mark.
    * Returns the mark owner's rating in the `ownerRating` field of the returned MarkData.  Note that if
    * `callingUser` owns the mark, the supplied optRef is completely ignored.
    */
  def mask(optRef: Option[MarkRef], callingUser: Option[User]): Mark = {
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
    * If the mark has been masked, show the original rating in blue, o/w lookup the mark's expected rating ID in
    * the given `eratings` map and show that in blue.  This allows us to (1) always show the current user's rating
    * in orange, even when displaying another shared-from user's mark, and (2) hide shared-from users' rating
    * predictions, which contain information about more than just the mark being shared, from shared-to users.
    */
  def blueRating(eratings: Map[ObjectId, ExpectedRating]): Option[Double] =
    if (mark.bMasked) mark.ownerRating else expectedRating.flatMap(eratings.get).flatMap(_.value)

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
      reprs = reprs ++ oth.reprs,
      pndPrivPages = pndPrivPages + oth.pndPrivPages
    )
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

object Mark extends BSONHandlers {
  val logger: Logger = Logger(classOf[Mark])

  // probably a good idea to log this somewhere, and this seems like a good place for it to only happen once
  logger.info("data-model version " + Option(getClass.getPackage.getImplementationVersion).getOrElse("null"))

  case class RangeMils(begin: TimeStamp, end: TimeStamp)
  type DurationMils = Long

  /**
    * Auxiliary stats pertaining to a `Mark`.
    *
    * The two `cachedTotal` vars will only be computed if they are None.  These fields are specifically excluded
    * from the MongoDB projection when searching so that they can be re-computed each time they're loaded.  At that
    * point, the caller can choose whether or not to keep the two `tab` vals.
    */
  case class MarkAux(tabVisible: Option[Seq[RangeMils]],
                     tabBground: Option[Seq[RangeMils]],
                     var cachedTotalVisible: Option[DurationMils] = None,
                     var cachedTotalBground: Option[DurationMils] = None) {

    /** Not using `copy` in this merge to ensure if new fields are added, they aren't forgotten here. */
    def merge(oth: MarkAux) =
      MarkAux(Some(tabVisible.getOrElse(Nil) ++ oth.tabVisible.getOrElse(Nil)),
              Some(tabBground.getOrElse(Nil) ++ oth.tabBground.getOrElse(Nil)))

    /** These methods return the total aggregated amount of time in the respective sequences of ranges. */
    if (cachedTotalVisible.isEmpty) cachedTotalVisible = Some(total(tabVisible))
    if (cachedTotalBground.isEmpty) cachedTotalBground = Some(total(tabBground))
    def totalVisible: DurationMils = tabVisible.fold(cachedTotalVisible.getOrElse(0L))(_ => total(tabVisible))
    def totalBground: DurationMils = tabBground.fold(cachedTotalBground.getOrElse(0L))(_ => total(tabBground))
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

  /**
    * Keep track of which URLs have identical content to other URLs, per user.  For example, the following 2 URLs:
    *  https://www.nature.com/articles/d41586-017-07522-z?utm_campaign=Data%2BElixir&utm_medium=email&utm_source=Data_Elixir_160
    *  https://www.nature.com/articles/d41586-017-07522-z
    *
    * The two `var`s are used for database lookup and index.  Their respective non-`var`s are the true values.  `dups`
    * is the thing being looked up--a list of other URLs that are duplicated content of `url`.
    */
  case class UrlDuplicate(userId: UUID,
                          url: String,
                          dups: Set[String],
                          var userIdPrfx: String = "", // why can't a simple string be used for urlPrfx also?
                          var urlPrfx: Option[mutable.WrappedArray[Byte]] = None,
                          id: String = generateDbId(Mark.ID_LENGTH)) {
    userIdPrfx = userId.toString.binPrfxComplement
    urlPrfx = Some(url.binaryPrefix)
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
  val PENDING_PAGES = nameOf[Mark](_.pndPrivPages)

  // `text` index search score <projectedFieldName>, not a field name of the collection
  val SCORE: String = nameOf[Mark](_.score)

  // the 'x' is for "extended" (changing these from non-x has already identified one bug)
  val SUBJx: String = MARK + "." + nameOf[MarkData](_.subj)
  val URLx: String = MARK + "." + nameOf[MarkData](_.url)
  val STARSx: String = MARK + "." + nameOf[MarkData](_.rating)
  val TAGSx: String = MARK + "." + nameOf[MarkData](_.tags)
  val COMNTx: String = MARK + "." + nameOf[MarkData](_.comment)
  val COMNTENCx: String = MARK + "." + nameOf[MarkData](_.commentEncoded)
  val PGPENDx: String = MARK + "." + nameOf[MarkData](_.pagePending)

  val REFIDx: String = nameOf[Mark](_.markRef) + "." + nameOf[MarkRef](_.markId)

  val TABVISx: String = AUX + "." + nameOf[MarkAux](_.tabVisible)
  val TABBGx: String = AUX + "." + nameOf[MarkAux](_.tabBground)
  val TOTVISx: String = AUX + "." + nameOf[MarkAux](_.totalVisible)
  val TOTBGx: String = AUX + "." + nameOf[MarkAux](_.totalBground)

  // ReprInfo constants value (note that these are not 'x'/extended as they aren't prefixed w/ "mark.")
  val REPR_TYPE: String = nameOf[ReprInfo](_.reprType)
  val REPR_ID: String = nameOf[ReprInfo](_.reprId)
  val EXP_RATING: String = nameOf[ReprInfo](_.expRating)

  // the 'p' is for "position" update operator
  val EXP_RATINGx: String = REPRS + "." + EXP_RATING
  val EXP_RATINGxp: String = REPRS + ".$." + EXP_RATING
//  val REPRIDxp: String = PRVEXPRAT + ".$." + nameOf[ReprInfo](_.reprId)

  val USRPRFX: String = nameOf[UrlDuplicate](_.userIdPrfx)
  assert(nameOf[UrlDuplicate](_.urlPrfx) == com.hamstoo.models.Mark.URLPRFX)
  assert(nameOf[UrlDuplicate](_.id) == com.hamstoo.models.Mark.ID)

  implicit val pageFmt: BSONDocumentHandler[Page] = Macros.handler[Page]
  implicit val shareGroupHandler: BSONDocumentHandler[ShareGroup] = Macros.handler[ShareGroup]
  implicit val sharedWithHandler: BSONDocumentHandler[SharedWith] = Macros.handler[SharedWith]
//  implicit val pageBsonHandler: BSONDocumentHandler[Page] = Macros.handler[Page]
  implicit val rangeBsonHandler: BSONDocumentHandler[RangeMils] = Macros.handler[RangeMils]
  implicit val auxBsonHandler: BSONDocumentHandler[MarkAux] = Macros.handler[MarkAux]
  implicit val eratingBsonHandler: BSONDocumentHandler[ExpectedRating] = Macros.handler[ExpectedRating]
  implicit val markRefBsonHandler: BSONDocumentHandler[MarkRef] = Macros.handler[MarkRef]
  implicit val markDataBsonHandler: BSONDocumentHandler[MarkData] = Macros.handler[MarkData]
  implicit val reprRating: BSONDocumentHandler[ReprInfo] = Macros.handler[ReprInfo]
  implicit val entryBsonHandler: BSONDocumentHandler[Mark] = Macros.handler[Mark]
  implicit val urldupBsonHandler: BSONDocumentHandler[UrlDuplicate] = Macros.handler[UrlDuplicate]
  implicit val markDataJsonFormat: OFormat[MarkData] = Json.format[MarkData]
}
