package com.hamstoo.stream

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.google.inject.name.Named
import com.hamstoo.models.Representation.Vec

/**
  * Define the (default) implementation of this facet.
  * @param queryVec  A semantic word vector representing the query terms.
  * @param reprVecs  A stream of a users' marks' representations' semantic word vectors.
  */
@Singleton
class QueryCorrelation @Inject() (@Named("query.vec") queryVec: Vec, reprVecs: ReprVec)
    extends DataStream[Double] {

  override val source: Source[Data[Double], NotUsed] = reprVecs.source.map { d =>
    val reprVec: Vec = d.oval.get.value

    // maybe should use actual corr here rather than cosine similarity?
    import com.hamstoo.models.Representation.VecFunctions
    val corr = reprVec cosine queryVec

    Datum[Double](d.oid.get, d.oval.get.sourceTime, d.knownTime, corr)
  }
}
