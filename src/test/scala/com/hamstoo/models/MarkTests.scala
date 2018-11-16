package com.hamstoo.models

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.test.FlatSpecWithMatchers
import org.apache.commons.text.StringEscapeUtils
import org.scalatest.OptionValues

/**
  * Mark model tests.
  */
class MarkTests extends FlatSpecWithMatchers with OptionValues {

  import com.hamstoo.utils.DataInfo._

  "Mark" should "(UNIT) markdown" in {
    val a = withComment("* a lonely list item")
    a.commentEncoded.get.replaceAll("\\s", "") shouldEqual "<ul><li>alonelylistitem</li></ul>"
    val b = withComment("hello markdown link conversion text [I'm an inline-kinda link](https://www.google.com)")
    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\"https://www.google.com\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      "I'm an inline-kinda link</a></p>"
  }

  it should "(UNIT) strikethrough (issue #316)" in {
    val c = withComment("BEFORE~~strikethrough~~then~~another~~AFTER")
    c.commentEncoded.get shouldEqual "<p>BEFORE<del>strikethrough</del>then<del>another</del>AFTER</p>"
    val d = withComment("~~strikethrough~~")
    d.commentEncoded.get shouldEqual "<p><del>strikethrough</del></p>"
    val e = withComment("~~not struck ~~")
    e.commentEncoded.get shouldEqual "<p>~~not struck ~~</p>" // no good b/c of the space after the 'k'
  }

  it should "(UNIT) image" in {

    var imgUrl = "http://www.fillmurray.com/100/100"
    val f = withComment("![filename](" + imgUrl + ")")
    f.commentEncoded.get shouldEqual "<p><img src=\"" + imgUrl + "\" alt=\"filename\"></p>"
    val g = withComment("text<img align=\"right\" width=\"100\" height=\"100\" src=\"" + imgUrl + "\">text")
    g.commentEncoded.get shouldEqual "<p>text<img align=\"right\" width=\"100\" height=\"100\" src=\"" + imgUrl + "\">text</p>"

    // should change `src` to `http-src` (and ends up swapping attr order b/c it removes and re-inserts)
    imgUrl = "http://localhost/api/v1/marks/img/skFnwYt41402bNtF"
    val h = withComment("<img src=\"" + imgUrl + "\" alt=\"1525871490532\">")
    h.commentEncoded.get shouldEqual "<img alt=\"1525871490532\" http-src=\"" + imgUrl + "\">"

    h.metaTags.size shouldBe 1
    h.metaTags("image") shouldEqual imgUrl
  }

  it should "(UNIT) meta" in {

    var m = withComment("<meta property=\"description\" content=\"testDescription\">")
    m.metaTags shouldBe Map("description" -> "testDescription")
    m.commentEncoded shouldBe Some("")

    m = withComment("<meta name=\"testName\" content=\"testNameContent\">")
    m.metaTags shouldBe Map("testName" -> "testNameContent")
    m.commentEncoded shouldBe Some("")

    // should be skipped b/c doesn't have a valid `name` or `property` attr
    m = withComment("<meta noname=\"testNoName\" content=\"testNoNameContent\">")
    m.metaTags.size shouldBe 0
    m.commentEncoded shouldBe Some("")

    // should be skipped b/c doesn't have a `content` attr
    m = withComment("<meta name=\"testNoContent\" nocontent=\"testNoContent\">")
    m.metaTags.size shouldBe 0
    m.commentEncoded shouldBe Some("")
  }

  val domainLink = "https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'"
  val ipLink0 = "https://216.58.209.99:90/"
  val ipLink1 = "https://234.234.234:80/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'"

  it should "(UNIT) markdown should detect embedded domain link" in {
    val orig = withComment("hello " + StringEscapeUtils.unescapeHtml4(domainLink))
    val parsed = "<p>hello <a href=\"" + StringEscapeUtils.escapeHtml4(domainLink) +
      "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4(domainLink) + "</a></p>"
    orig.commentEncoded.get shouldEqual parsed
  }

  it should "(UNIT) markdown should detect embedded IP link" in {
    val orig = withComment("hello " + StringEscapeUtils.unescapeHtml4(ipLink0))
    val parsed = "<p>hello <a href=\"" + StringEscapeUtils.escapeHtml4(ipLink0) +
      "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4(ipLink0) + "</a></p>"
    orig.commentEncoded.get shouldEqual parsed
  }

  it should "(UNIT) find embedded link with IP and tag it as <a> tag" in {
    val orig = "<p>hello " + ipLink0 + " </p>"
    val parsed = "<p>hello <a href=\"" + ipLink0 + "\">" + ipLink0 + "</a> </p>"
    MarkdownNodesVisitor.parseLinksInTextOrExtractUrl(orig) shouldEqual parsed
  }

  it should "(UNIT) find embedded link with domain name and tag it as <a> tag" in {
    val orig = "<p>hello " + domainLink + " </p>"
    val parsed = "<p>hello <a href=\"" + domainLink + "\">" + domainLink + "</a> </p>"
    MarkdownNodesVisitor.parseLinksInTextOrExtractUrl(orig) shouldEqual parsed
  }

  it should "(UNIT) skip <a> tagged link in function `embeddedLinksToHtmlLinks`" in {
    val orig = " <p>hello <a href=\"https://www.google.com\">I'm an inline-kinda link</a></p>"
    val parsed = " <p>hello <a href=\"https://www.google.com\">I'm an inline-kinda link</a></p>"
    MarkdownNodesVisitor.parseLinksInTextOrExtractUrl(orig) shouldEqual parsed
  }

  it should "(UNIT) skip and whitelist <a> tagged link in function `commentEncoded`" in {
    val orig = withComment("hello " +
      StringEscapeUtils.unescapeHtml4("<a href=\"https://www.google.com\">I'm an inline-kinda link</a>"))
    val parsed = "<p>hello <a href=\"" + StringEscapeUtils.escapeHtml4("https://www.google.com") +
      "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4("I'm an inline-kinda link") + "</a></p>"
    orig.commentEncoded.get shouldEqual parsed
  }

  it should "(UNIT) try to prevent XSS attacks from comments" in {
    // https://www.owasp.org/index.php/XSS_Filter_Evasion_Cheat_Sheet
    val a = withComment("<SCRIPT SRC=http://xss.rocks/xss.js></SCRIPT>")
    a.commentEncoded.get shouldEqual ""
    val b = a.copy(comment = Some("<IMG SRC=JaVaScRiPt:alert('XSS')>"))
    b.commentEncoded.get shouldEqual "<p><img></p>"
    val c = a.copy(comment = Some("<IMG SRC=`javascript:alert(\"RSnake says, 'XSS'\")`>"))
    c.commentEncoded.get shouldEqual "<p><img>javascript:alert(\"RSnake says, 'XSS'\")&gt;</p>"
    val d = a.copy(comment = Some("<IMG SRC=javascript:alert(String.fromCharCode(88,83,83))>"))
    d.commentEncoded.get shouldEqual "<img>"
    val e = a.copy(comment = Some("<IMG SRC=&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;&#97;&#108;&#101;&#114;&#116;&#40;&#39;&#88;&#83;&#83;&#39;&#41;>"))
    e.commentEncoded.get shouldEqual "<img>"
    val f = a.copy(comment = Some("'';!--\"<XSS>=&{()}"))
    f.commentEncoded.get shouldEqual "<p>'';!--\"=&amp;{()}</p>"
    val g = a.copy(comment = Some("hello <a name=\"n\" href=\"javascript:alert('xss')\">*you*</a>"))
    g.commentEncoded.get shouldEqual "<p>hello <a rel=\"nofollow noopener noreferrer\" target=\"_blank\"><em>you</em></a></p>"
  }

  it should "(UNIT) try to prevent XSS attacks from url" in {
    MarkData.sanitize("unsafe:javascript:alert('xss')") shouldBe None
  }

  it should "(UNIT) be mergeable" in {
    // test merge (would be nice to test warning messages due to non matching field values also)
    val merged = mA.merge(mB)

    merged.mark.subj shouldEqual mdA.subj
    merged.mark.url shouldEqual mdA.url
    merged.mark.rating shouldEqual mdB.rating // B!
    merged.mark.tags.get shouldEqual (mdA.tags.get ++ mdB.tags.get)
    merged.mark.comment.get shouldEqual (mdA.comment.get + "\n\n---\n\n" + mdB.comment.get)
    merged.pubRepr shouldEqual mA.pubRepr
    merged.reprs shouldEqual mA.reprs :+ reprInfoPrivB
  }

  it should "(UNIT) throw an exception when merging marks with different userIds" in {
    // different userIds should throw an AssertionError
    intercept[AssertionError] {
      val c = Mark(constructUserId(), mark = mdB)
      mA.merge(c)
    }
  }

  it should "(UNIT) correctly retrieve marks info" in {
    val unrated = ReprInfo("someid", ReprType.PRIVATE)
    val rated = unrated.copy(reprId = "someId1", expRating = Some("rat"))
    val m = Mark(constructUserId(), mark = mdA, reprs = Seq(unrated))
    val mRated = m.copy(reprs = Seq(rated))

    m.unratedPrivRepr.get shouldEqual unrated.reprId
    mRated.unratedPrivRepr shouldEqual None
  }
}
