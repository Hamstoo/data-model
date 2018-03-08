package com.hamstoo.stream

import java.util.UUID

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.google.inject.name.Named
import com.google.inject.{Inject, Singleton}
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao}
import com.hamstoo.utils.{DurationMils, TimeStamp}
import com.hamstoo.models.Representation.{Vec, VecEnum}

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * A stream of a user's mark's representation vectors.
  * @param userId  The UUID of the user's marks represented by this stream.
  */
@Singleton
case class ReprVec @Inject() (@Named("user.id") userId: UUID,
                              marksDao: MongoMarksDao,
                              reprsDao: MongoRepresentationDao)
                             (implicit clock: Clock,
                              m: Materializer) // implicits don't really do much when using Guice/DI, do they?
    extends DataSource[Vec] {

  // TODO: should ReprVec just have an apply method like GroupReduce and pass the UUID in through there?
  // TODO: or should we reserve apply for the DSL?

  /** Map a stream of marks to their reprs' PC1 vectors. */
  override def load(beginExcl: TimeStamp, endIncl: TimeStamp): Future[immutable.Iterable[Datum[Vec]]] = {

    marksDao.stream(userId, beginExcl, endIncl).mapAsync(4) { mark =>

      // get the mark's primaryRepr and map its PC1 vector to a Datum
      reprsDao.retrieve(mark.primaryRepr).map {
        _.flatMap { repr =>
          repr.vectors.get(VecEnum.PC1.toString).map { vec =>
            Datum(MarkId(mark.id), mark.timeFrom, vec)
          }
        }
      }
    }.collect { case Some(z) => z } // remove Nones (flatten doesn't appear to exist)
      .runWith(Sink.seq) // materialize to Iterable
  }

}
