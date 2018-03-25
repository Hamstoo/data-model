package com.hamstoo.models

import com.github.dwickern.macros.NameOf.nameOf
import com.hamstoo.utils.ExtendedString
import org.apache.commons.text.StringEscapeUtils
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import play.api.Logger

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
  extends MDSearchable(subj, url, rating, tags, comment) with Protected[MarkData] {

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

  /** Instead of merge, it provide patch functionallity */
  def patch(mdp: MarkDataPatch): MarkData = {
    MarkData(subj = mdp.subj.getOrElse(subj),
      url = if (mdp.url.isDefined) mdp.url else url,
      rating = if (mdp.rating.isDefined) mdp.rating else rating,
      tags = if (mdp.tags.isDefined) mdp.tags else tags,
      comment = if (mdp.comment.isDefined) mdp.comment else comment)
  }

  /** If the two MarkDatas are equal, as far as generating a public representation would be concerned. */
  // TODO: the interface for constructing a public repr should only be allowed to include these fields via having its own class
  def equalsPerPubRepr(other: MarkData): Boolean =
    url.isDefined && url == other.url || url.isEmpty && subj == other.subj

  /** If the two MarkDatas are equal, as far as generating a user representation would be concerned. */
  // TODO: the interface for constructing a user repr should only be allowed to include these fields via having its own class
  def equalsPerUserRepr(other: MarkData): Boolean =
    copy(url = None, rating = None) == other.copy(url = None, rating = None)

  override def protect: MarkData = {
    copy(
      subj = subj.sanitize,
      url = url.map(_.sanitize),
      comment = comment.map(_.sanitize),
      tags = tags.map(_.map(_.sanitize))
    )
  }
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

  // the 'x' is for "extended" (changing these from non-x has already identified one bug)
  val SUBJ: String = nameOf[MarkData](_.subj)
  val URL: String = nameOf[MarkData](_.url)
  val STARS: String = nameOf[MarkData](_.rating)
  val TAGS: String = nameOf[MarkData](_.tags)
  val COMNT: String = nameOf[MarkData](_.comment)
  val COMNTENC: String = nameOf[MarkData](_.commentEncoded)
}

case class MarkDataPatch(subj: Option[String],
                         url: Option[String],
                         rating: Option[Double],
                         tags: Option[Set[String]],
                         comment: Option[String]) extends Protected[MarkDataPatch] {

  override def protect: MarkDataPatch = {
    copy(subj = subj.map(_.sanitize),
      url = url.map(_.sanitize),
      tags = tags.map(_.map(_.sanitize)),
      comment = comment.map(_.sanitize))
  }
}
