package com.hamstoo.models

import java.util.UUID

import org.specs2.mutable.Specification


/**
  * Mark model tests.
  */
class MarkSpec extends Specification {

  "Mark" should {
    "* be consistently hashable, regardless of its `score`" in {
      val userId = UUID.randomUUID
      val a = Mark(userId, mark = MarkData("a subject", None))
      val b = a.copy(score = Some(3.4))
      a.hashCode mustEqual b.hashCode
      a mustEqual b
    }

    "* markdown" in {
      val a = MarkData("", None, None, None, Some("* a lonely list item"), None)
      a.commentEncoded.get.replaceAll("\\s", "") mustEqual "<ul><li>alonelylistitem</li></ul>"
    }

    "* try to prevent XSS attacks" in {
      // https://www.owasp.org/index.php/XSS_Filter_Evasion_Cheat_Sheet
      val a = MarkData("", None, None, None, Some("<SCRIPT SRC=http://xss.rocks/xss.js></SCRIPT>"), None)
      a.commentEncoded.get mustEqual ""
      val b = a.copy(comment = Some("<IMG SRC=JaVaScRiPt:alert('XSS')>"))
      b.commentEncoded.get mustEqual "<p><img></p>"
      val c = a.copy(comment = Some("<IMG SRC=`javascript:alert(\"RSnake says, 'XSS'\")`>"))
      c.commentEncoded.get mustEqual "<p><img>javascript:alert(\"RSnake says, 'XSS'\")&gt;</p>"
      val d = a.copy(comment = Some("<IMG SRC=javascript:alert(String.fromCharCode(88,83,83))>"))
      d.commentEncoded.get mustEqual "<img>"
      val e = a.copy(comment = Some("<IMG SRC=&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;&#58;&#97;&#108;&#101;&#114;&#116;&#40;&#39;&#88;&#83;&#83;&#39;&#41;>"))
      e.commentEncoded.get mustEqual "<img>"
      val f = a.copy(comment = Some("'';!--\"<XSS>=&{()}"))
      f.commentEncoded.get mustEqual "<p>'';!--\"=&amp;{()}</p>"
      val g = a.copy(comment = Some("hello <a name=\"n\" href=\"javascript:alert('xss')\">*you*</a>"))
      g.commentEncoded.get mustEqual "<p>hello <a rel=\"nofollow noopener noreferrer\" target=\"_blank\"><em>you</em></a></p>"
    }

    "* be mergeable" in {
      import com.hamstoo.specUtils.{mdA, mdB, mA, mB}

      // test merge (would be nice to test warning messages due to non matching field values also)
      val merged = mA.merge(mB)

      merged.mark.subj mustEqual mdA.subj
      merged.mark.url mustEqual mdA.url
      merged.mark.rating mustEqual mdB.rating // B!
      merged.mark.tags.get mustEqual (mdA.tags.get ++ mdB.tags.get)
      merged.mark.comment.get mustEqual (mdA.comment.get + "\n\n---\n\n" + mdB.comment.get)
      merged.pubRepr mustEqual mA.pubRepr
      merged.privRepr mustEqual mA.privRepr

      // different userIds should throw an AssertionError
      val c = Mark(UUID.randomUUID, mark = mdB)
      mA.merge(c) must throwA[AssertionError]
    }
  }
}
