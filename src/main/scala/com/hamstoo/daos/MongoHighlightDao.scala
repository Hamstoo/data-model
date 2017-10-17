package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{HLPosition, Highlight, Mark, PageCoord}
import org.joda.time.DateTime
import play.api.Logger
import reactivemongo.api.DefaultDB
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.commands.bson.DefaultBSONCommandError
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import reactivemongo.bson.{BSONArray, BSONDocumentHandler, Macros}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Data access object for highlights.
  */
class MongoHighlightDao(db: Future[DefaultDB]) extends MongoContentDao[Highlight] {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.utils._

  override val futColl: Future[BSONCollection] = db map (_ collection "highlights")
  private val marksColl: Future[BSONCollection] = db map (_ collection "entries")

  override val log = Logger(classOf[MongoHighlightDao])

  // convert url/uPrefs to markIds
  case class WeeHighlight(usrId: UUID, id: String, timeFrom: Long, url: String)

  for {
    c <- futColl
    mc <- marksColl

    oldIdx = s"bin-$USR-1-uPref-1"
    _ = log.info(s"Dropping index $oldIdx")
    nDropped <- c.indexesManager.drop(oldIdx).recover { case _: DefaultBSONCommandError => 0 }
    _ = log.info(s"Dropped $nDropped index(es)")

    exst = d :~ "$exists" -> true
    sel = d :~ "$or" -> BSONArray(d :~ "url" -> exst, d :~ "uPref" -> exst)
    urled <- {
      implicit val r: BSONDocumentHandler[WeeHighlight] = Macros.handler[WeeHighlight]
      c.find(sel).coll[WeeHighlight, Seq]()
    }
    _ = log.info(s"Updating ${urled.size} Highlights with markIds (and removing their URLs)")
    _ <- Future.sequence { urled.map { x => for { // lookup mark w/ same url
      marks <- mc.find(d :~ Mark.USR -> x.usrId :~ Mark.URLPRFX -> x.url.binaryPrefix).coll[Mark, Seq]()
      markId = marks.headOption.map(_.id).getOrElse("")
      _ <- c.update(d :~ ID -> x.id :~ TIMEFROM -> x.timeFrom,
                    d :~ "$unset" -> (d :~ "url" -> 1 :~ "uPref" -> 1) :~ "$set" -> {d :~ "markId" -> markId},
                    multi = true)
    } yield () }}
  } yield ()

  // indexes with names for this mongo collection
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: MARKID -> Ascending :: Nil) % s"bin-$USR-1-$MARKID-1" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap
  
  futColl map (_.indexesManager ensure indxs)

  /** Update timeThru on an existing highlight and insert a new one with modified values. */
  def update(usr: UUID,
             id: String,
             pos: HLPosition,
             prv: Highlight.Preview,
             coord: Option[PageCoord]): Future[Highlight] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
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
