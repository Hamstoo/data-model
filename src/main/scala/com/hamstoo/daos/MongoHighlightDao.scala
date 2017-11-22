package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Highlight, Mark, PageCoord}
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.commands.bson.DefaultBSONCommandError
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONArray, BSONDocumentHandler, Macros}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
  * Data access object for highlights.
  */
class MongoHighlightDao(db: () => Future[DefaultDB]) extends MongoAnnotationDao[Highlight]("highlight", db) {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.utils._

  override val logger = Logger(classOf[MongoHighlightDao])
  override def dbColl(): Future[BSONCollection] = db().map(_ collection "highlights")

  // convert url/uPrefs to markIds
  case class WeeHighlight(usrId: UUID, id: String, timeFrom: Long, url: String)

  // data migration
  Await.result(for {
    c <- dbColl()
    mc <- marksColl()
    _ = logger.info(s"Performing data migration for `highlights` collection")

    oldIdx = s"bin-$USR-1-uPref-1"
    _ = logger.info(s"Dropping index $oldIdx")
    nDropped <- c.indexesManager.drop(oldIdx).recover { case _: DefaultBSONCommandError => 0 }
    _ = logger.info(s"Dropped $nDropped index(es)")

    exst = d :~ "$exists" -> true
    sel = d :~ "$or" -> BSONArray(d :~ "url" -> exst, d :~ "uPref" -> exst)
    urled <- {
      implicit val r: BSONDocumentHandler[WeeHighlight] = Macros.handler[WeeHighlight]
      c.find(sel).coll[WeeHighlight, Seq]()
    }
    _ = logger.info(s"Updating ${urled.size} Highlights with markIds (and removing their URLs)")
    _ <- Future.sequence { urled.map { x => for { // lookup mark w/ same url
      marks <- mc.find(d :~ Mark.USR -> x.usrId :~ Mark.URLPRFX -> x.url.binaryPrefix).coll[Mark, Seq]()
      markId = marks.headOption.map(_.id).getOrElse("")
      _ <- c.update(d :~ ID -> x.id :~ TIMEFROM -> x.timeFrom,
                    d :~ "$unset" -> (d :~ "url" -> 1 :~ "uPref" -> 1) :~ "$set" -> {d :~ "markId" -> markId},
                    multi = true)
    } yield () }}
  } yield (), 105 seconds)

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: MARKID -> Ascending :: Nil) % s"bin-$USR-1-$MARKID-1" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  Await.result(dbColl() map (_.indexesManager ensure indxs), 45 seconds)

  /** Update timeThru on an existing highlight and insert a new one with modified values. */
  def update(usr: UUID,
             id: String,
             pos: Highlight.Position,
             prv: Highlight.Preview,
             coord: Option[PageCoord]): Future[Highlight] = for {
    c <- dbColl()
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c.findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    hl = wr.result[Highlight].get.copy(pos = pos,
                                       preview = prv,
                                       pageCoord = coord,
                                       memeId = None,
                                       timeFrom = now,
                                       timeThru = INF_TIME)
    wr <- c insert hl
    _ <- wr failIfError
  } yield hl
}
