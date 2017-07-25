package services

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}
import org.specs2.mutable.Specification


/**
  * Mark model tests.
  */
class MarkSpec extends Specification {

  "Mark" should {
    "* be consistently hashable, regardless of its `score`" in {
      val uuid = UUID.randomUUID
      val a = Mark.apply(uuid, MarkData("a subject", None, None, None, None), None)
      val b = a.copy(score = Some(3.4))
      a.hashCode mustEqual b.hashCode
      a mustEqual b
    }
  }
}
