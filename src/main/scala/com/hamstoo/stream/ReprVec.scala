package com.hamstoo.stream

import java.util.UUID

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.hamstoo.daos.{MongoMarksDao, MongoRepresentationDao, MongoUserDao}
import com.hamstoo.models.Representation.{Vec, VecEnum}
import reactivemongo.api.DefaultDB

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * A stream of a user's representation's vectors.
  * @param userId  The UUID of the user's marks represented by this stream.
  */
case class ReprVec(userId: UUID)(implicit db: () => Future[DefaultDB], m: Materializer) extends DataStream[Vec] {

  private val marksDao = new MongoMarksDao(db)(new MongoUserDao(db))
  private val reprsDao = new MongoRepresentationDao(db)

  /** Map a stream of marks to their reprs' PC1 vectors. */
  override val source: Source[Datum, NotUsed] = marksDao.stream(userId).mapAsync(4) { mark =>
    // get the mark's primaryRepr and map its PC1 vector to a Datum
    reprsDao.retrieve(mark.primaryRepr).map { _.flatMap { repr =>
      repr.vectors.get(VecEnum.PC1.toString).map { vec =>
        Datum(ReprId(repr.id), mark.timeFrom, mark.timeFrom, vec)
      }
    }}
  }.collect { case Some(x) => x }

}
