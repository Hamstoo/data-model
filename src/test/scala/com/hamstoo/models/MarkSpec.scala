package com.hamstoo.models

import java.util.UUID

import com.hamstoo.utils.{DataInfo, FlatSpecWithMatchers}

/**
  * Mark model tests.
  */
class MarkSpec extends FlatSpecWithMatchers with DataInfo {

  "Mark" should "* be consistently hashable, regardless of its `score`" in {
    val uuid = UUID.randomUUID
    val a = Mark(uuid, mark = MarkData("a subject", None))
    val b = a.copy(score = Some(3.4))
    a.hashCode shouldEqual b.hashCode
    a shouldEqual b
  }

  it should "* markdown" in {
    val a = MarkData("", None, None, None, Some("* a lonely list item"), None)
    a.commentEncoded.get.replaceAll("\\s", "") shouldEqual "<ul><li>alonelylistitem</li></ul>"
    val b = a.copy(comment = Some("hello markdown link conversion text [I'm an inline-style link](https://www.google.com)"))
    b.commentEncoded.get shouldEqual "<p>hello markdown link conversion text " +
      "<a href=\"https://www.google.com\" rel=\"nofollow noopener noreferrer\" target=\"_blank\">" +
      "I'm an inline-style link</a></p>"
  }

  it should "* try to prevent XSS attacks" in {
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

  it should "* be mergeable" in {
    // test merge (would be nice to test warning messages due to non matching field values also)
    val merged = mA.merge(mB)

    merged.mark.subj shouldEqual mdA.subj
    merged.mark.url shouldEqual mdA.url
    merged.mark.rating shouldEqual mdB.rating // B!
    merged.mark.tags.get shouldEqual (mdA.tags.get ++ mdB.tags.get)
    merged.mark.comment.get shouldEqual (mdA.comment.get + "\n\n---\n\n" + mdB.comment.get)
    merged.pubRepr shouldEqual mA.pubRepr
    merged.privRepr shouldEqual mA.privRepr

  }

  //    it should "* throw exception in different UUID" in {
  //      // different userIds should throw an AssertionError
  //
  //      val thrown = intercept[AssertionError] {
  //        val c = Mark(UUID.randomUUID, mark = mdB)
  //        mA.merge(c)
  //      }
  //
  //      thrown shouldBe a [AssertionError]
  //    }
}
