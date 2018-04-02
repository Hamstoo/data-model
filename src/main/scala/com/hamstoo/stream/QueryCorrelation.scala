package com.hamstoo.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import com.google.inject.name.Named
import com.hamstoo.models.Representation.Vec

import scala.concurrent.{ExecutionContext, Future}

/**
  * Define the (default) implementation of this facet.
  * @param queryVec  A semantic word vector representing the query terms.
  * @param reprVecs  A stream of a users' marks' representations' semantic word vectors.
  */
@com.google.inject.Singleton
class QueryCorrelation @Inject() (@Named("query.vec") queryVec: Future[Vec],
                                  reprVecs: ReprsStream)
                                 (implicit materializer: Materializer, ec: ExecutionContext)
    extends DataStream[Double] {

  // can only obtain an EC from an ActorMaterializer via `.system`, not from a plain old Materializer
  //implicit val ec: ExecutionContext = materializer.system.dispatcher

  override val hubSource: Source[Data[Double], NotUsed] = reprVecs.source.mapAsync(1) { d =>

    // queryVec is probably an already-completed Future at this point, and will stay that way
    queryVec.map { qvec: Vec =>

      val reprVec: Vec = d.oval.get.value

      // maybe should use actual corr here rather than cosine similarity?
      import com.hamstoo.models.Representation.VecFunctions
      val corr = VecFunctions(reprVec) cosine qvec

      Datum[Double](d.oid.get, d.oval.get.sourceTime, d.knownTime, corr)
    }
  }
}
