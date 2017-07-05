package services

import com.hamstoo.models.Representation
import org.specs2.mutable.Specification


/**
  * Representation model tests.
  */
class RepresentationSpec extends Specification {

  "Representation" should {
    "* be consistently hashable" in {
      val a = Representation("", Some("xyz"), None, "", "", "", "", None, 0)
      val b = Representation("", Some("xyz"), None, "", "", "", "", None, 0)
      a.hashCode mustEqual b.hashCode
      a mustEqual b
    }
  }
}
