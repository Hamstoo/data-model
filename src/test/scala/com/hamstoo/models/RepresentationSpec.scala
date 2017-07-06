package services

import com.hamstoo.models.Representation
import org.specs2.mutable.Specification


/**
  * Representation model tests.
  */
class RepresentationSpec extends Specification {

  "Representation" should {
    "* be consistently hashable" in {
      def rep = Representation("", Some("xyz"), None, "", "", "", "", None, 0, Long.MaxValue)
      val (a, b) = (rep, rep)
      a.hashCode mustEqual b.hashCode
      a mustEqual b
    }
  }
}
