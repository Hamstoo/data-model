package com.hamstoo.stream

import java.util.UUID

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
import com.hamstoo.models.Representation.{Vec, VecEnum}
import com.hamstoo.stream.Clock.Clock
import com.hamstoo.utils.TimeStamp
import reactivemongo.api.DefaultDB

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * A stream of a user's representation's vectors.
  * @param userId  The UUID of the user's marks represented by this stream.
  */
//@Singleton // cannot be singleton b/c of `userId` but how do other types of DataStreams be made so?
case class ReprVec(userId: UUID)(implicit clock: Clock, db: () => Future[DefaultDB], m: Materializer)
                                                                                        extends DataSource[Vec] {

  private val marksDao = new MongoMarksDao(db)(new MongoUserDao(db), implicitly)
  private val reprsDao = new MongoRepresentationDao(db)

  // TODO: should ReprVec just have an apply method like Reducer and pass the UUID in through there?
  // TODO: or should we reserve apply for the DSL?

  /** Map a stream of marks to their reprs' PC1 vectors. */
  override def load(beginExcl: TimeStamp, endIncl: TimeStamp): Future[immutable.Iterable[Datum[Vec]]] = {

    marksDao.stream(userId, beginExcl, endIncl).mapAsync(4) { mark =>

      // get the mark's primaryRepr and map its PC1 vector to a Datum
      reprsDao.retrieve(mark.primaryRepr).map {
        _.flatMap { repr =>
          repr.vectors.get(VecEnum.PC1.toString).map { vec =>
            Datum(ReprId(repr.id), mark.timeFrom, mark.timeFrom, vec)
          }
        }
      }
    }.collect { case Some(z) => z } // remove Nones (flatten doesn't appear to exist)
      .runWith(Sink.seq) // materialize to Iterable
  }

}
