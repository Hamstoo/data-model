package com.hamstoo.services

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.hamstoo.daos.MongoVectorsDao
import com.hamstoo.models.Representation
import com.hamstoo.models.Representation._
import com.hamstoo.services.VectorEmbeddingService.WordMass
import com.hamstoo.utils.{FlatSpecWithMatchers, FutureHandler, MongoEnvironment, TestHelper}
import play.api.libs.ws.ahc.AhcWSClient

/**
  * VectorEmbeddingsService tests.
  *
  * If any of these tests fail with the following error "java.util.NoSuchElementException: None.get"
  * then it's possible that the conceptnet5-vectors-docker container isn't reachable.
  */
class VectorEmbeddingsServiceTests
  extends TestKit(ActorSystem("VectorEmbeddingsServiceSpec-ActorSystem"))
    with FlatSpecWithMatchers
    with MongoEnvironment
    with FutureHandler
    with TestHelper {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)

    super.afterAll()
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  lazy val vectorizer = new Vectorizer(AhcWSClient(), new MongoVectorsDao(getDB), vectorsLink)
  lazy val idfModel = new IDFModel(idfsResource)
  lazy val vecSvc = new VectorEmbeddingsService(vectorizer, idfModel)

  // skip all of these tests because CircleCI doesn't have access to the conceptnet-vectors container

  "VectorEmbeddingsService" should "IDF vectorize" ignore {


    val header0 = "Futures and Promises - Scala Documentation Futures and Promises"
    //val header0 = "Getting Started Building a Google Chrome Extension"
    val header1 = "Futures% !&and 'Promises' - Scala Documentation, \"Futures,$ and? Promises."
    val vec0 = vecSvc.vectorEmbeddings(header0, "", "", "")._1(Representation.VecEnum.IDF)
    val vec1 = vecSvc.vectorEmbeddings(header1, "", "", "")._1(Representation.VecEnum.IDF)
    vec0.head shouldEqual 8.61e-5 +- 1e-7
    vec0.head shouldEqual vec1.head +- 1e-15

  }

  it should "produce similar vecs in some cases (test is NON-DETERMINISTIC; if it fails try re-running it" ignore {


    import com.hamstoo.models.Representation._

    val terms_ = Seq("man", "woman",
      "boy", "girl",
      "king", "queen",
      "dad", "mom",
      "father", "mother",
      "grandfather", "grandmother",
      "uncle", "aunt",
      "nephew", "niece",
      "prince", "princess")

    // randomly shuffle on all but the first iteration
    val means: Seq[Double] = for {
      i <- 0 until 5
      terms = if (i == 0) terms_ else scala.util.Random.shuffle(terms_)
    } yield {
      val vecs: Seq[Vec] = terms.map(vecSvc.vectorEmbeddings(_, "", "", "")._1(Representation.VecEnum.IDF))
      val diffs: Seq[Vec] = vecs.sliding(2, 2).map { case a :: b :: Nil => a - b }.toSeq

      // these values range between 0.0 and 0.95 for the unshuffled terms, still I thought they'd be higher
      val cosines: Seq[(Double, String, String)] = for {
        i <- diffs.indices
        j <- diffs.indices
        if i < j
      } yield (diffs(i) cosine diffs(j), terms(i * 2), terms(j * 2))

      //cosines.sorted.foreach { case (cos, t0, t1) => println(s"    $t0 * $t1 = $cos") }

      val mu: Double = cosines.map(_._1).mean
      val sigma: Double = cosines.map(_._1).stdev
      val z = mu / sigma
      val n = cosines.length
      val t = z * Math.sqrt(n)
      println(f"i = $i, mean = $mu%.3f, stdev = $sigma%.3f, Z = $z%.3f, t = $t%.3f, n = $n")
      mu
    }

    val meanMeanDiff: Double = means.head - means.tail.mean
    println(f"meanMeanDiff = $meanMeanDiff%.3f")
    meanMeanDiff should be > 0.4

  }

  it should "produce similar vectors in other cases" ignore {

    import com.hamstoo.models.Representation._

    val terms = Seq("otter", "european_otter",
      "otter", "otterlike",
      "actor", "star_in_film",
      "actor", "histrion",
      "sachin", "cricket", // 0.522 - http://api.conceptnet.io/related/c/en/sachin?filter=/c/en/cricket
      "actor", "cumberbatch") // 0.516 - http://api.conceptnet.io/related/c/en/actor?filter=/c/en/cumberbatch

    val vecs: Seq[Vec] = terms.map(vecSvc.vectorEmbeddings(_, "", "", "")._1(Representation.VecEnum.IDF))
    vecs.foreach {
      _.l2Norm shouldEqual 1.0 +- 1e-8
    }

    val cosines: Seq[Double] = vecs.sliding(2, 2).map { case a :: b :: Nil => a cosine b }.toSeq
    cosines.zip(terms.sliding(2, 2).toSeq).foreach {
      case (cos, t0 :: t1 :: Nil) => println(s"    $t0 * $t1 = $cos")
      case _ =>
    }

    // these values are different (higher actually) than those listed here:
    //    https://groups.google.com/forum/#!topic/conceptnet-users/yL5QP9uGyfQ
    // (see both the June 15 and June 19 comments, which even have different values from each other)
    // and here: https://groups.google.com/forum/#!topic/conceptnet-users/GfDZ4AoPc60
    // the differences are probably due to the use of the ConceptNet knowledge graph which averages in the vectors
    //    of nearest neighbors (the main purpose of which is to handle OOV words)
    cosines.head shouldEqual 0.996 +- 1e-3 // 0.978
    cosines(1) shouldEqual 0.897 +- 1e-3 // 0.800
    cosines(2) shouldEqual 0.993 +- 1e-3 // 0.969
    cosines(3) shouldEqual 0.977 +- 1e-3 // 0.905
    cosines(4) shouldEqual 0.346 +- 1e-3 // 0.522
    cosines(5) shouldEqual 0.563 +- 1e-3 // 0.516

  }

  it should "select top words" ignore {


    val txt = "otter otter european_otter otters otterlike toyota ford car"
    val topWords: Seq[WordMass] = vecSvc.text2TopWords(txt).futureValue._1
    // 2 words are duplicates and out of the remaining 7 only 5 are kept per `text2TopWords.desiredFracWords` function
    topWords.size shouldEqual 5

  }

  it should "k-means vectorize" ignore {


    val txt = "otter european_otter otter otters otterlike toyota ford car"
    val topWords: Seq[WordMass] = vecSvc.text2TopWords(txt).futureValue._1
    val (vecs, _) = vecSvc.text2KMeansVecs(topWords, 2)

    Seq(("otter", 0.956, -0.590),
      ("car", -0.374, 0.423), // note that "car" is filtered out by `text2TopWords`
      ("ford", -0.626, 0.630),
      ("toyota", -0.482, 0.846)).foreach { case (w, s0, s1) =>

      val wordVec = vectorizer.dbCachedLookupFuture(vectorizer.ENGLISH, w).futureValue.get._1
      vecs.head.cosine(wordVec) shouldEqual s0 +- 1e-3
      vecs(1).cosine(wordVec) shouldEqual s1 +- 1e-3
    }
  }
}
