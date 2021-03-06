/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
package com.hamstoo.models

import java.net.{URL, URLEncoder}
import java.util.UUID

import com.github.dwickern.macros.NameOf._
import com.hamstoo.daos.{ImageDao, MarkDao, RepresentationDao}
import com.hamstoo.models.Mark.MarkAux
import com.hamstoo.models.Representation.ReprType
import com.hamstoo.utils
import com.hamstoo.utils.{DurationMils, ExtendedString, INF_TIME, MediaType, MetaType, NON_IDS, ObjectId, TIME_NOW, TimeStamp, generateDbId}
import org.apache.commons.text.StringEscapeUtils
import org.commonmark.node._
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Whitelist
import play.api.Logger
import play.api.libs.json.{Json, OFormat}
import reactivemongo.bson.{BSONDocumentHandler, BSONObjectID, Macros}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.matching.Regex


/**
  * User content data model. This case class is also used for front-end JSON formatting.
  *
  * @param subj     the rated element as a string of text; either a header of the marked page, or the rated
  *                   string itself
  * @param url      an optional url
  * @param rating   the value assigned to the mark by the user, from 0.0 to 5.0
  * @param tags     a set of tags assigned to the mark by the user
  * @param comment  an optional text comment assigned to the mark by the user
  * @param recId    An optional recommendation ID from whence this mark originated.
  */
case class MarkData(subj: String,
                    url: Option[String],
                    rating: Option[Double] = None,
                    tags: Option[Set[String]] = None,
                    comment: Option[String] = None,
                    recId: Option[BSONObjectID] = None) {

  import MarkData._

  // when a Mark gets masked by a MarkRef, bMasked will be set to true and ownerRating will be set to the original
  // rating value (not part of the data(base) model)
  var bMasked: Boolean = false
  var ownerRating: Option[Double] = None

  // <meta> HTML tags cannot appear in <body>, which is where commentEncoded will appear, so extract them into their
  // own map so that they can be applied later in <head> by the frontend's metaTagsService/metaTags.service.js
  val metaTags: mutable.Map[String, String] = mutable.Map.empty[String, String]

  // populate `commentEncoded` by deriving it from the value of `comment` (no need for this to be in the database)
  val commentEncoded: Option[String] = comment.map { c: String => // example: <IMG SRC=JaVaScRiPt:alert('XSS')>

    // example: <p>&lt;IMG SRC=JaVaScRiPt:alert('XSS')&gt;</p>
    // https://github.com/atlassian/commonmark-java
    // https://spec.commonmark.org/
    // as well parses and renders markdown markup language
    val commarkDoc: org.commonmark.node.Node = parser.parse(c)

    // detects embedded links in text only and make them clickable (issue #136)
    // ignores html links (anchors) to avoid double tags because commonmark.parser.parse(...) does not allocate <a>
    // to separate nodes, so such tags would o/w be double tagged like <a href...><a href...>Link</a></a>
    commarkDoc.accept(MarkdownNodesVisitor())

    val html0 = renderer.render(commarkDoc)

    // example: <p><IMG SRC=JaVaScRiPt:alert('XSS')></p>
    // convert that &ldquo; back to a < character
    val html1 = StringEscapeUtils.unescapeHtml4(html0)

    // reset meta tags
    metaTags.clear()

    // select all `meta` nodes with a `content` attr (these are later removed by htmlTagsWhitelist as they're not
    // allowed to appear in HTML <body> anyway)
    for(node <- Jsoup.parse(html1).select("meta[content]").toArray) yield {
      val e = node.asInstanceOf[Element]

      // OpenGraph uses `property`, but everything else uses `name`, which doesn't really matter because the attr
      // names are discarded (the frontend's metaTagsService will correct them either way)
      val key = e.attr("name") match { case x if x.nonEmpty => x; case x => e.attr("property") }
      if (key.nonEmpty)
        metaTags(key) = e.attr("content")
    }

    // example: <p><img></p>
    // must be applied before converting `src` attrs b/c it does special stuff w/ them that it doesn't do w/ http-src
    val html2 = Jsoup.clean(html1, htmlTagsWhitelist)

    // attention: mutable Java object
    val jsoupDoc: org.jsoup.nodes.Document = Jsoup.parse(html2)

    // select all `img` nodes with a `src` attribute (pointing to one of our images) and change attr key to `http-src`
    // (per issue #317 here: https://github.com/Hamstoo/hamstoo/issues/317#issuecomment-387517901)
    for(node <- jsoupDoc.select("img[src]").toArray) yield {
      val e = node.asInstanceOf[Element]
      val srcValue = e.attr("src")
      if (MarkData.isHamstooImage(srcValue)) {
        e.removeAttr("src")
        e.attr("http-src", srcValue)
      }

      // use the first image as the <meta> tag image
      if (!metaTags.contains(MetaType.IMAGE))
        metaTags(MetaType.IMAGE) = srcValue
    }

    // apply whitelist again to remove html/head/body tags that JSoup's Document.toString adds in (super clean!)
    val html3 = jsoupDoc.toString
    Jsoup.clean(html3, htmlTagsWhitelist)
  }

  /**
    * Mutator method!  Populate `metaTags` map with additional image-related tags, which Facebook needs.
    *   https://developers.facebook.com/docs/sharing/opengraph/object-properties/
    *
    * Also, Facebook requires an extension (ugh) so just tack it on to the end of the "image" element, and
    * then take it off in ImageDao (if it's there).  We don't need it for anything.
    */
  def setImageMetaTags(imageDao: ImageDao)(implicit ec: ExecutionContext): Future[Unit] = {

    metaTags.get(MetaType.IMAGE)
      .filter(MarkData.isHamstooImage)
      .flatMap(_.split('/').lastOption)
      .fold(Future.unit) { imgId =>

        logger.debug(s"Setting image meta tags for image ID $imgId")

        imageDao.retrieve(imgId).map { mbImg =>
          mbImg.fold(Unit) { img =>

            // append an extension to satisfy the Facebook gods
            img.mimeType.filter(_.startsWith(MediaType.IMG_ROOT))
              .map(_.substring(MediaType.IMG_ROOT.length))
              .foreach { ext0 =>
                val ext = if (ext0.nonEmpty && ext0 != "*") ext0 else "png" // exclude "image/*", just guess
                metaTags(MetaType.IMAGE) += "." + ext
                logger.debug(s"File extension appended to `${MetaType.IMAGE}` meta tag: ${metaTags(MetaType.IMAGE)}")
              }

            val imgTags = Seq(
              img.width.map(MetaType.OG_IMAGE_WIDTH -> _.toString),
              img.height.map(MetaType.OG_IMAGE_HEIGHT -> _.toString),
              img.mimeType.map(MetaType.OG_IMAGE_TYPE -> _)
            ).flatten

            logger.info(s"Image ID $imgId image meta tags: $imgTags")

            // can't do a `copy` b/c metaTags would get overwritten during computation of commentEncoded
            //this.copy(metaTags = withImgTags)
            metaTags ++= imgTags
            Unit
          }
        }
      }
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
  def equalsPerPubRepr(that: MarkData): Boolean =
    url.isDefined && url == that.url || url.isEmpty && subj == that.subj

  /** If the two MarkDatas are equal, as far as generating a user representation would be concerned. */
  // TODO: the interface for constructing a user repr should only be allowed to include these fields via having its own class
  def equalsPerUserRepr(that: MarkData): Boolean =
    copy(url = None, rating = None) == that.copy(url = None, rating = None)
}

object MarkData {
  val logger: Logger = Logger(classOf[MarkData])

  // attention: mutable Java classes below

  // for markdown parsing/rendering
  lazy val parser: Parser = Parser.builder().build()
  lazy val renderer: HtmlRenderer = HtmlRenderer.builder().build()

  // for XSS filtering (https://jsoup.org/cookbook/cleaning-html/whitelist-sanitizer)
  lazy val htmlTagsWhitelist: Whitelist = Whitelist.relaxed()
    .addTags("hr") // horizontal rule
    .addTags("del").addTags("s") // strikethrough
    .addTags("div").addAttributes("div", "style") // to allow text-align and such
    .addAttributes("img", "http-src", "style")
    .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer") // https://medium.com/@jitbit/target-blank-the-most-underestimated-vulnerability-ever-96e328301f4c
    .addEnforcedAttribute("a", "target", "_blank")

  val commentMergeSeparator: String = "\n\n---\n\n"

  // this tag is present in calls to backend's MarksController.saveMark when browser extension automark feature is on,
  // this value must match the value in chrome-extension's timeTracker.js
  val AUTOSAVE_TAG = "Automarked"
  val IMPORT_TAG = "Imported"
  val UPLOAD_TAG = "Uploaded"
  val SHARED_WITH_ME_TAG = "SharedWithMe"
  val RECOMMENDATION_TAG = "Recommendation" // for marks that were originally recommendations

  /**
    * Check string for validity and sanitize string from dangerous XSS content. By default it works with HTML based content.
    * First of all in parse it, by attributes and then filter it by list of supported tags.
    * All example can be find in tests.
    * @param url  string that must be sanitized
    * @return     sanitized string
    */
  def sanitize(url: String): Option[String] =
    Try {
      // this next line will change "file:///home/fred/Downloads/Baxter&Crimins_2018.pdf" (note the triple '/'s) to
      // "file:/home/fred/Downloads/Baxter&Crimins_2018.pdf", which we don't want it to do (because it breaks
      // retrieveByUrl), so undo it if it happens
      val javaNetUrl = new URL(url).toString
      val pfx = "file://" // "file:///" gets changed to "file:/" but any other number of slashes remains unchanged
      if (url.startsWith(pfx) && !javaNetUrl.startsWith(pfx))
        pfx + javaNetUrl.substring(pfx.length - 2)
      else
        javaNetUrl
    }
      .map(Jsoup.clean(_: String, MarkData.htmlTagsWhitelist))
      .toOption
      // added 2018-6-8 after retrieveByUrl was found broken due to '&'s being converted to '&amp;'s in URLs
      // in MarksController.add
      .map(StringEscapeUtils.unescapeHtml4)

  /** Identifies "our images" */
  def isHamstooImage(imgUrl: String): Boolean = imgUrl.contains("api/v1/marks/img")
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
  * This class is used to detect embedded links in text and wrap them w/ <a> anchor tags.  It also parses
  * strikethrough which the Commonmark Markdown parser does not handle.
  *
  * This class only visits Markdown nodes because any HTML put in the comment by the user does not, itself,
  * get parsed into nodes, which would require a call to Jsoup.parse.
  */
case class MarkdownNodesVisitor() extends AbstractVisitor {
  import MarkdownNodesVisitor._

  override def visit(htmlBlock: HtmlBlock): Unit = {
    htmlBlock.setLiteral(parseStrikethrough(htmlBlock.getLiteral)) // TODO: use Jsoup.parse here instead
    visitChildren(htmlBlock)
  }

  override def visit(text: Text): Unit = {
    val lit0 = text.getLiteral

    // find and wrap links
    val lit1 = parseLinksInTextOrExtractUrl(lit0).get

    // process strikethrough, but if there were any embedded links then just punt on it
    val lit2 = parseStrikethrough(lit1) // TODO: Jsoup.parse

    // apply changes to currently visiting text node
    text.setLiteral(lit2)

    visitChildren(text)
  }

  /*override protected def visitChildren(parent: Node): Unit = {
    var node = parent.getFirstChild
    while(node != null) {

      Logger.error(s"NODE = $node")

      // A subclass of this visitor might modify the node, resulting in getNext returning a different node or no
      // node after visiting it. So get the next node before visiting.
      val next = node.getNext
      node.accept(this)
      node = next
    }
  }*/

}

object MarkdownNodesVisitor {

  /**
    * This function takes HTML (as a String), as opposed to straight-up text, because it has already undergone
    * _some_ parsing.
    *
    * TODO: it would probably be better if this function took a Jsoup.parse nodes tree as input rather than a String
    */
  def parseStrikethrough(html: String): String = Try {

    // process double '~'s into <del> tags (there has to be a better way, this code is inelegance at its finest)
    // explanation of rules implemented below: https://talk.commonmark.org/t/feature-request-strikethrough-text/220/5
    // additional support: https://webapps.stackexchange.com/questions/14986/strikethrough-with-github-markdown

    // the top of a Stack is its head position
    val strikes = mutable.Stack[(Int, Option[Int])]()

    def isOpen: Boolean = strikes.headOption.exists(_._2.isEmpty)

    // pad beginning and ending of string so that we can iterate each char by 4-tuples
    var insideTag = false
    val quadable = " " + html + "  "
    (0 until html.length).foreach { i: Int =>
      val quad = quadable.substring(i, i + 4)
      (quad(0), quad(1), quad(2), quad(3)) match {

        case ('<', _, _, _) if !insideTag => insideTag = true

        // neither `a` nor `b` can be '~' to satisfy both #1s below
        case (a, '~', '~', b) if a != '~' && b != '~' =>

          if (a == '>' && insideTag) insideTag = false

          // > A double ~~ can open strikethrough iff:
          // >   1. it is not part of a sequence of 3 or more unescaped '~'s, and
          // >   2. it is not followed by whitespace
          if (!b.isWhitespace && !isOpen) strikes.push((i, None))

          // > A double ~~ can close strikethrough iff:
          // >   1. it is not part of a sequence of 3 or more unescaped '~'s, and
          // >   2. it is not preceded by whitespace
          else if (!a.isWhitespace && isOpen) strikes.push(strikes.pop.copy(_2 = Some(i)))

        case ('>', _, _, _) if insideTag => insideTag = false

        case _ =>
      }
    }

    // iterate in reverse because the top of a Stack is its head position
    var previousEndPlus2 = 0
    strikes.reverse.collect {
      case (b, Some(e)) => // ignore unclosed strikethroughs: (b, None)

        val sub = html.substring(previousEndPlus2, b) + "<del>" + html.substring(b + 2, e) + "</del>"
        previousEndPlus2 = e + 2
        sub
    }.mkString("") +
      html.substring(previousEndPlus2, html.length)

  }.getOrElse(html)

  val IGNORE_TAGS_AND_FIND_URL_REGEX: Regex = (
      "(?<!href=\")" + // ignore http pattern prepended by 'href=' expression
      "((?:https?|ftp)://)" + // check protocol
      "(([a-zA-Z0-‌​9\\-\\._\\?\\,\\'/\\+&am‌​p;%\\$#\\=~])*[^\\.\\,\\)\\(\\s])" // allowed anything which is allowed in url
    ).r

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
    *
    *   this function is also used to extract the url from text(i.e. DiscussionController)
    *   so, isUrlRequired is set as true, just simply return the url that matches to regex
    */
  def parseLinksInTextOrExtractUrl(text: String, isUrlRequired: Boolean = false): Option[String] = {
    val regex = IGNORE_TAGS_AND_FIND_URL_REGEX
    if (isUrlRequired)
      regex.findFirstIn(text)
    else
      Some(regex.replaceAllIn(text, m => "<a href=\"" + m.group(0) + "\">" + m.group(0) + "</a>"))
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
  * @param userId     Owner's user ID.
  * @param sharedWith Defines which other users are allowed to read or write this Mark[Data].
  * @param id         The mark's alphanumerical string, used as an identifier common with all the marks versions.
  * @param mark       User-provided content.
  * @param markRef    Content that references (and masks) another mark that is owned by a different user.
  *                     When this field is provided, the `mark` field should be empty.
  *                     TODO: What if this non-owner goes and marks the same URL?  Just create another mark!
  * @param aux        MarkAux: Additional fields holding satellite data
  * @param urlPrfx    Binary prefix of `mark.url` for the purpose of indexing by mongodb; set by class init
  *                     Binary prefix is used as filtering and 1st stage of urls equality estimation
  *                     https://en.wikipedia.org/wiki/Binary_prefix
  * @param reprs      A history of different mark states/views/representations as marked by the user
  * @param timeFrom   Timestamp of last edit.
  * @param timeThru   The moment of time until which this version is latest.
  * @param mergeId    If this mark was merged into another, this will be the ID of that other.
  *
  * @param score      `score` is not part of the documents in the database, but it is returned from
  *                     `MongoMarksDao.search` so it is easier to have it included here.
  */
case class Mark(override val userId: UUID,
                override val id: ObjectId = generateDbId(Mark.ID_LENGTH),
                mark: MarkData,
                markRef: Option[MarkRef] = None,
                aux: Option[MarkAux] = Some(MarkAux(None, None)),
                var urlPrfx: Option[mutable.WrappedArray[Byte]] = None, // using *hashable* WrappedArray here
                reprs: Seq[ReprInfo] = Nil,
                timeFrom: TimeStamp = TIME_NOW,
                timeThru: TimeStamp = INF_TIME,
                modifiedBy: Option[UUID] = None,
                mergeId: Option[String] = None,
                override val sharedWith: Option[SharedWith] = None,
                override val nSharedFrom: Option[Int] = Some(0),
                override val nSharedTo: Option[Int] = Some(0),
                private val created: Option[TimeStamp] = Some(TIME_NOW),
                score: Option[Double] = None)
    extends Shareable {

  import Mark._

  // Even though this is stored in the database, it still can't hurt to enforce that it's kept inline with url.
  urlPrfx = mark.url map (_.binaryPrefix)

  /**
    * We haven't always been storing `created` field in the database, so it's a little trickier for those marks
    * for which it doesn't exist.  TODO: Update the database across the board.
    */
  def created1(implicit markDao: MarkDao): Future[Option[TimeStamp]] =
    created.fold(markDao.retrieveCreationTime(id))(t => Future.successful(Some(t)))

  /** Compose a mark's `id` with its `subj` so that we can include it in the mark's URL. */
  def idWithSubj: String = id + ID_SUBJ_SEP + urlEncodedSubj
  def urlEncodedSubj: String = {
    val encodedSubj = utils.parseAN(mark.subj).replaceAll(" ", "_").take(100)
    URLEncoder.encode(if (encodedSubj.isEmpty) "empty" else encodedSubj, "UTF-8")
  }

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
    * A Mark that has a pointer to a MarkRef should be used to `mask` a Mark/MarkData of another user (that has been
    * shared with this user) rather than doing anything with it's own MarkData.  It's own MarkData will have a URL
    * populated, but that's it, so that it can be found by MarkDao.retrieveByUrl.
    */
  def isRef: Boolean = markRef.nonEmpty

  /**
    * Mask a Mark's MarkData with a MarkRef--for the viewing pleasure of a sharee (shared-with, non-owner of the Mark).
    * Returns the mark owner's rating in the `ownerRating` field of the returned MarkData.  Note that if `callingUser`
    * owns the mark, the supplied mbRef is completely ignored (even though it should probably be None in that case).
    */
  def mask(mbRef: Option[MarkRef], callingUserId: Option[UUID]): Mark = {
    if (callingUserId.exists(ownedBy)) this else {

      // still mask even if mbRef is None to ensure that the owner's rating is moved to the `ownerRating` field
      val ref = mbRef.getOrElse(MarkRef(id)) // create an empty MarkRef (id isn't really used)

      val unionedTags = mark.tags.getOrElse(Set.empty[String]) ++ ref.tags.getOrElse(Set.empty[String])
      val mdata = mark.copy(rating = ref.rating,
                            tags = if (unionedTags.isEmpty) None else Some(unionedTags))

      // no need to copy facets as they aren't set until the end of search, while MarkRef masking occurs during search
      mdata.bMasked = true // even though mdata is a val we are still allowed to do this--huh!?!
      mdata.ownerRating = mark.rating
      logger.debug(s"Mark $id masked rating ${mdata.ownerRating}->${mdata.rating} and labels $unionedTags")
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
    * Returning an Option[String] would be more "correct" here, but returning the empty string just makes things a
    * lot cleaner on the other end.  However alas, note not doing so for `expectedRating`.
    */
  def primaryRepr: ObjectId  = privRepr.orElse(pubRepr).getOrElse("")

  /**
    * Auto-generated keywords for a mark depend on the size of its primary page repr and user-content repr.
    * User content is generally much more relevant to a mark than the webpage text, which we still haven't yet
    * refined (issue #214).  So this method reconciles the two into a single list of keywords.  Very roughly,
    * it takes about 2/3 of the user-content repr keywords and combines them with 1/3 of the page repr keywords.
    */
  def autoGenKws(implicit reprDao: RepresentationDao): Future[Option[Seq[String]]] = {
    import com.hamstoo.utils.ExecutionContext.CachedThreadPool.global
    val fprimary = reprDao.retrieve(primaryRepr)
    val fuser = reprDao.retrieve(userContentRepr.getOrElse(""))
    for { rP <- fprimary; rU <- fuser } yield {

      val nP = rP.flatMap(_.nWords).getOrElse(0L)
      val nU = rU.flatMap(_.nWords).getOrElse(0L)
      val kwsP = rP.flatMap(_.autoGenKws).getOrElse(Seq.empty[String])
      val kwsU = rU.flatMap(_.autoGenKws).getOrElse(Seq.empty[String])
      logger.debug(s"Mark.autoGenKws: nP=$nP, nU=$nU, kwsP.size=${kwsP.size}, kwsU.size=${kwsU.size}")

      import com.hamstoo.services.VectorEmbeddingsService.{N_DESIRED_KEYWORDS => N}
      //val halfN = N / 2
      //val kws = if (nU > 100 && kwsU.size > halfN) kwsU // if there are lots of user keywords, use those
      //          else if (nU < 10 || kwsU.size < 3) kwsP // if there aren't many user keywords, use page keywords
      //          else (kwsU.take(halfN) ++ kwsP).take(N) // if there are a few user keywords, use half and half
      val subsetU = kwsU.filterNot(kwsP.contains).take(math.min(nU / 5, N * 2 / 3).toInt)
      val kws = (subsetU ++ kwsP).take(N)
      if (kws.isEmpty) None else Some(kws)
    }
  }

  /**
    * Basically the same impl as `primaryRepr` except that it returns an expected rating ID (as an Option).
    * Update, 2018-8-31: Now returns user-content repr's expected rating if both private & public are missing.
    */
  def expectedRating: Option[ObjectId] = {

    /** Analogous to `primary def repr`. */
    def mostRecentValidExpRatingOfType(isType: ReprInfo => Boolean): Option[ObjectId] = reprs
      .filter(x => isType(x) && !NON_IDS.contains(x.expRating.getOrElse("")))
      .filter(_.expRating.isDefined)
      .sortBy(_.created).lastOption.flatMap(_.expRating)

    val priv             = mostRecentValidExpRatingOfType(_.isPrivate)
    lazy val pub         = mostRecentValidExpRatingOfType(_.isPublic)
    lazy val userContent = mostRecentValidExpRatingOfType(_.isUserContent)

    priv.orElse(pub).orElse(userContent)
  }

  /** Returns true if this Mark's MarkData has all of the test tags.  Should be consistent w/ MarkDao.hasTags. */
  def hasTags(testTags: Set[String]): Boolean = testTags.forall(t => mark.tags.exists(_.map(_.toLowerCase)
                                                                                       .contains(t.toLowerCase)))

  /**
    * Return latest representation ID, if it exists.  Even though public and user-content reprs are supposed to
    * be singletons, it doesn't hurt to be conservative and return the most recent rather than just using `find`.
    */
  def privRepr: Option[ObjectId] = repr(x => x.isPrivate)
  def pubRepr: Option[ObjectId] = repr(_.isPublic)
  def userContentRepr: Option[ObjectId] = repr(_.isUserContent)
  private def repr(pred: ReprInfo => Boolean): Option[ObjectId] =
    reprs.filter(x => pred(x) && !NON_IDS.contains(x.reprId)).sortBy(_.created).lastOption.map(_.reprId)

  /**
    * If the mark has been masked, show the original rating in blue, o/w lookup the mark's expected rating ID in
    * the given `eratings` map and show that in blue.  This allows us to (1) always show the current user's rating
    * in orange, even when displaying another shared-from user's mark, and (2) hide shared-from users' expected
    * ratings, which contain information about more than just the mark being shared, from shared-to users.
    *
    * The reason this takes a map as an argument is because backend search, MarksController.list, performs a single
    * batch query for the rating predictions of all of the search results and passes the batch in here.
    *
    * @param mbCallingUserId  Who's asking?  The expected rating is only shown (as blueRating) if the mark is
    *                         being viewed by its owner.
    */
  def blueRating(eratings: Map[ObjectId, ExpectedRating], mbCallingUserId: Option[UUID]): Option[Double] =
    if (mark.bMasked) mark.ownerRating
    else if (mbCallingUserId.exists(ownedBy)) expectedRating.flatMap(eratings.get).flatMap(_.value)
    else mark.rating

  /** Convenience function that converts from an Option to a Map. */
  def blueRating(mbERating: Option[ExpectedRating], mbCallingUserId: Option[UUID]): Option[Double] =
    blueRating(mbERating.toSeq.map(er => er.id -> er).toMap, mbCallingUserId)

  /**
    * The orange rating should always be the rating of the user who is viewing the mark.  If the mark has been
    * masked the owner's rating has been moved to the `ownerRating` field and replaced with the viewer's
    * (calling user's) rating.  If the viewer does not own the mark and it hasn't been masked then don't show
    * an orange rating at all.
    */
  def orangeRating(mbCallingUserId: Option[UUID]): Option[Double] =
    if (mark.bMasked) mark.rating
    else if (mbCallingUserId.exists(ownedBy)) mark.rating
    else None

  /** Same as `equals` except ignoring timeFrom/timeThru. */
  def equalsIgnoreTimeStamps(that: Mark): Boolean =
    equals(that.copy(timeFrom = timeFrom, timeThru = timeThru, score = score))
}

object Mark extends BSONHandlers {
  val logger: Logger = Logger(classOf[Mark])

  // probably a good idea to log this somewhere, and this seems like a good place for it to only happen once
  logger.info("data-model version " + Option(getClass.getPackage.getImplementationVersion).getOrElse("null"))

  // a separator to separate a mark's `id` from its `subj` when composing its singleMark/FPV URL
  val ID_SUBJ_SEP = "-"

  /**
    * If the mark's subj has been embedded in the URL then that part is ignored, if not then we respond
    * with a permanent redirect to the subj-embedded URL.
    */
  def parseIdSubj(idSubj: String): (String, Option[String]) = {
    val parts = idSubj.split(ID_SUBJ_SEP, 2)
    (parts.head, parts.lift(1))
  }

  case class RangeMils(begin: TimeStamp, end: TimeStamp)

  /**
    * Auxiliary stats pertaining to a `Mark`.
    *
    * The two `total` vars will only be computed if their respective `tab` vals are non-None so that the latter can
    * be removed (see `cleanRanges`) and the former preserved to reduce the memory footprint of MarkAux instances.
    */
  case class MarkAux(tabVisible: Option[Seq[RangeMils]],
                     tabBground: Option[Seq[RangeMils]],
                     nOwnerVisits: Option[Int] = Some(0),
                     nShareeVisits: Option[Int] = Some(0),
                     nUnauthVisits: Option[Int] = Some(0),
                     var totalVisible: Option[DurationMils] = None,
                     var totalBground: Option[DurationMils] = None) {

    /** Not using `copy` in this merge to ensure if new fields are added, they aren't forgotten here. */
    def merge(oth: MarkAux) =
      MarkAux(Some(tabVisible.getOrElse(Nil) ++ oth.tabVisible.getOrElse(Nil)),
              Some(tabBground.getOrElse(Nil) ++ oth.tabBground.getOrElse(Nil)),
              Some(nOwnerVisits.getOrElse(0) + oth.nOwnerVisits.getOrElse(0)),
              Some(nShareeVisits.getOrElse(0) + oth.nShareeVisits.getOrElse(0)),
              Some(nUnauthVisits.getOrElse(0) + oth.nUnauthVisits.getOrElse(0)))

    /** These methods return the total aggregated amount of time in the respective sequences of ranges. */
    if (tabVisible.nonEmpty) totalVisible = Some(total(tabVisible))
    if (tabBground.nonEmpty) totalBground = Some(total(tabBground))
    def total(tabSomething: Option[Seq[RangeMils]]): DurationMils =
      tabSomething.map(_.foldLeft(0L)((agg, range) => agg + range.end - range.begin)).getOrElse(0L)

    /** Returns a copy with the two sequences of RangeMils removed--to preserve memory. */
    def cleanRanges: MarkAux = this.copy(tabVisible = None, tabBground = None)
  }

  /**
    * Expected rating for a mark (or recommendation) including the number of other marks that went into generating it
    * and when it was generated (and how long it was "active" for).
    * @param id   This is a randomly generated alphanumeric ID, which is used by marks to reference their eratings.
    *             It can also be a MongoDB BSONObjectID.stringify'ed specified by the user, which is used the same way
    *             by Recommendations, but in which case the respective Recommendations have the same `_id` values.
    */
  case class ExpectedRating(id: String = generateDbId(Mark.ID_LENGTH),
                            value: Option[Double],
                            n: Int,
                            similarReprs: Option[Seq[String]] = None,
                            timeFrom: Long = TIME_NOW,
                            timeThru: Long = INF_TIME) extends ReprEngineProduct[ExpectedRating] {

    override def withTimeFrom(timeFrom: Long): ExpectedRating = this.copy(timeFrom = timeFrom)
  }

  object ExpectedRating {
    val DEFAULT_VALUE: Double = 2.5 // TODO: perhaps should use a user's average rating rather than this 2.5 const
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

  /**
    * This class is only used to get a projection of userId field from mark BSONDocument to optimize performance of db query.
    * See also: https://docs.mongodb.com/manual/tutorial/optimize-query-performance-with-indexes-and-projections/#use-projections-to-return-only-necessary-data
    */
  case class UserId(userId: String)

  /**
    * This class is only used to get a projection of id field from mark BSONDocument to optimize performance of db query
    * look at https://docs.mongodb.com/manual/tutorial/optimize-query-performance-with-indexes-and-projections/#use-projections-to-return-only-necessary-data
    */
  case class Id(id: String)

  val ID_LENGTH: Int = 16

  val ID: String = Shareable.ID
  val USR: String = Shareable.USR
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
  val SUBJnox: String = nameOf[MarkData](_.subj)
  val SUBJx: String = MARK + "." + SUBJnox
  val URLx: String = MARK + "." + nameOf[MarkData](_.url)
  val STARSnox: String = nameOf[MarkData](_.rating)
  val STARSx: String = MARK + "." + STARSnox
  val TAGSnox: String = nameOf[MarkData](_.tags)
  val TAGSx: String = MARK + "." + TAGSnox
  val COMNTx: String = MARK + "." + nameOf[MarkData](_.comment)

  val COMNTENCnox: String = nameOf[MarkData](_.commentEncoded)
  val METATAGSnox: String = nameOf[MarkData](_.metaTags)
  val DESC = "description" // <- meta tags key

  val REF: String = nameOf[Mark](_.markRef)
  val REFIDx: String = REF + "." + nameOf[MarkRef](_.markId)
  assert(TAGSnox == nameOf[MarkRef](_.tags))
  val REFTAGSx: String = REF + "." + TAGSnox

  val TABVISx: String = AUX + "." + nameOf[MarkAux](_.tabVisible)
  val TABBGx: String = AUX + "." + nameOf[MarkAux](_.tabBground)
  val OVISITSx: String = AUX + "." + nameOf[MarkAux](_.nOwnerVisits)
  val SVISITSx: String = AUX + "." + nameOf[MarkAux](_.nShareeVisits)
  val UVISITSx: String = AUX + "." + nameOf[MarkAux](_.nUnauthVisits)

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

  implicit val bsonObjIdJFmt: OFormat[BSONObjectID] = Json.format[BSONObjectID]
  implicit val markDataJFmt: OFormat[MarkData] = Json.format[MarkData]
}
