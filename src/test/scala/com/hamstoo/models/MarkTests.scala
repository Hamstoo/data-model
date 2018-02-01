package com.hamstoo.models

import com.hamstoo.models.Representation.ReprType
import com.hamstoo.test.FlatSpecWithMatchers
import com.hamstoo.utils.TIME_NOW
import org.apache.commons.text.StringEscapeUtils
import org.scalatest.OptionValues

/**
  * Mark model tests.
  */
class MarkTests extends FlatSpecWithMatchers with OptionValues {

  import com.hamstoo.utils.DataInfo._

  "Mark" should "(UNIT) be consistently hashable, regardless of its `score`" in {
    val a = Mark(constructUserId(), mark = MarkData("a subject", None))
    val b = a.copy(score = Some(3.4))
    a.hashCode shouldEqual b.hashCode
    a shouldEqual b
  }

  it should "(UNIT) markdown" in {
    val a = MarkData("", None, None, None, Some("* a lonely list item"), None)
    a.commentEncoded.get.replaceAll("\\s", "") shouldEqual "<ul><li>alonelylistitem</li></ul>"
    val b = a.copy(comment = Some("hello markdown link conversion text [I'm an inline-style link](https://www.google.com)"))
    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\"https://www.google.com\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      "I'm an inline-style link</a></p>"
  }

  it should "(UNIT) markdown should detect embedded domain link" in {

    val b = emptyMarkData.copy(comment = Some("hello markdown link conversion text "+
      StringEscapeUtils.unescapeHtml4("https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'" +
        "&kjdk9238493kmfdsdfdsf='sdf'")))

    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\""+StringEscapeUtils.escapeHtml4(
      "https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'")+
      "\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4("https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'" +
        "&kjdk9238493kmfdsdfdsf='sdf'")+"</a></p>"
  }

  it should "(UNIT) markdown should detect embedded ip link" in {

    val b = emptyMarkData.copy(comment = Some("hello markdown link conversion text "+
      StringEscapeUtils.unescapeHtml4("https://216.58.209.99:90/")))

    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\""+StringEscapeUtils.escapeHtml4(
      "https://216.58.209.99:90/")+"\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4("https://216.58.209.99:90/")+"</a></p>"
  }

  it should "(UNIT) find embedded link with ip and tag it as <a> tag" in {

    val nonFilteredOfEmbeddedLinksTags = "<p>hello embedded link in text " +
      "https://234.234.234:80/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf' </p>"

    val filteredOfEmbeddedLinksTags = "<p>hello embedded link in text " +
      "<a href=\"https://234.234.234:80/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'\">" +
      "https://234.234.234:80/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'</a> </p>"

    val s1 = TextNodesVisitor.embeddedLinksToHtmlLinks(nonFilteredOfEmbeddedLinksTags)
    println(filteredOfEmbeddedLinksTags)
    println(s1)
    s1 shouldEqual filteredOfEmbeddedLinksTags
  }

  it should "(UNIT) find embedded link with domain name and tag it as <a> tag" in {

    val nonFilteredOfEmbeddedLinksTags = "<p>hello embedded link in text " +
      "https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf' </p>"

    val filteredOfEmbeddedLinksTags = "<p>hello embedded link in text " +
      "<a href=\"https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'\">" +
      "https://www.test.thedomain.level3-internet.com/someendpoint?askdjsk=0&asjdjhj='1'&kjdk9238493kmfdsdfdsf='sdf'</a> </p>"

    val s1 = TextNodesVisitor.embeddedLinksToHtmlLinks(nonFilteredOfEmbeddedLinksTags)
    println(filteredOfEmbeddedLinksTags)
    println(s1)
    s1 shouldEqual filteredOfEmbeddedLinksTags
  }

  it should "(UNIT) skip <a> tagged link in function `embeddedLinksToHtmlLinks` " in {

    val nonFilteredOfEmbeddedLinksTags =
      " <p>hello markdown link conversion text " +
      "<a href=\"https://www.google.com\">" +
      "I'm an inline-style link</a></p>"

    val filteredOfEmbeddedLinksTags =
      " <p>hello markdown link conversion text " +
      "<a href=\"https://www.google.com\">" +
      "I'm an inline-style link</a></p>"

    val s1 = TextNodesVisitor.embeddedLinksToHtmlLinks(nonFilteredOfEmbeddedLinksTags)
    println(filteredOfEmbeddedLinksTags)
    println(s1)
    s1 shouldEqual filteredOfEmbeddedLinksTags
  }

  it should "(UNIT) skip and whitelist <a> tagged link in function `commentEncoded`" in {

    val b = emptyMarkData.copy(comment = Some("hello markdown link conversion text "+
      StringEscapeUtils.unescapeHtml4("<a href=\"https://www.google.com\">I'm an inline-style link</a>")))

    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\""+StringEscapeUtils.escapeHtml4(
      "https://www.google.com")+"\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      StringEscapeUtils.escapeHtml4("I'm an inline-style link")+"</a></p>"
  }

    it should "(UNIT) try to prevent XSS attacks" in {
      // https://www.owasp.org/index.php/XSS_Filter_Evasion_Cheat_Sheet
      val a = MarkData("", None, None, None, Some("<SCRIPT SRC=http://xss.rocks/xss.js></SCRIPT>"), None)
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

    it should "(UNIT) be mergeable" in {
      // test merge (would be nice to test warning messages due to non matching field values also)
      val merged = mA.merge(mB)

      merged.mark.subj shouldEqual mdA.subj
      merged.mark.url shouldEqual mdA.url
      merged.mark.rating shouldEqual mdB.rating // B!
      merged.mark.tags.get shouldEqual (mdA.tags.get ++ mdB.tags.get)
      merged.mark.comment.get shouldEqual (mdA.comment.get + "\n\n---\n\n" + mdB.comment.get)
//      merged.pubRepr shouldEqual mA.pubRepr
      merged.reprs shouldEqual mA.reprs :+ reprInfoPrivB
    }

  it should "(UNIT) throw an exception when merging marks with different userIds" in {
    // different userIds should throw an AssertionError

    intercept[AssertionError] {
      val c = Mark(constructUserId(), mark = mdB)
      mA.merge(c)
    }
  }

  it should "(UNIT) corrctly retrieve marks info" in {
    val unrated = ReprInfo("someid", ReprType.PRIVATE, TIME_NOW)
    val rated = unrated.copy(reprId = "someId1", expRating = Some("rat"))
    val m = Mark(constructUserId(), mark = mdA, reprs = Seq(unrated))
    val mRated = m.copy(reprs = Seq(rated))

    m.unratedPrivRepr.get shouldEqual unrated.reprId
    mRated.unratedPrivRepr shouldEqual None
  }
}
