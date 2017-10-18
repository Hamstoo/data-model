package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, InlineNotePosition, Mark, PageCoord}
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
  * Data access object for inline notes (of which there can be many per mark) as opposed to comments (of which there
  * is only one per mark).
  */
class MongoInlineNoteDao(db: Future[DefaultDB]) extends MongoContentDao[InlineNote]("InlineNotes") {

  import com.hamstoo.models.InlineNote._
  import com.hamstoo.utils._

  override val futColl: Future[BSONCollection] = db map (_ collection "comments")
  private val marksColl: Future[BSONCollection] = db map (_ collection "entries")

  override val log = Logger(classOf[MongoInlineNoteDao])

  // convert url/uPrefs to markIds
  case class WeeNote(usrId: UUID, id: String, timeFrom: Long, url: String)

  for {
    c <- futColl
    mc <- marksColl

    oldIdx = s"bin-$USR-1-uPref-1"
    _ = log.info(s"Dropping index $oldIdx")
    nDropped <- c.indexesManager.drop(oldIdx).recover { case _: DefaultBSONCommandError => 0 }
    _ = log.info(s"Dropped $nDropped index(es)")

    exst = d :~ "$exists" -> true
    sel = d :~ "$or" -> BSONArray(d :~ "url" -> exst, d :~ "uPref" -> exst)
    // pjn = d :~ USR -> 1 :~ ID -> 1 :~ TIMEFROM -> 1 :~ "url" -> 1 // unnecessary b/c WeeNote is a subset
    urled <- {
      implicit val r: BSONDocumentHandler[WeeNote] = Macros.handler[WeeNote]
      c.find(sel).coll[WeeNote, Seq]()
    }
    _ = log.info(s"Updating ${urled.size} InlineNotes with markIds (and removing their URLs)")
    _ <- Future.sequence { urled.map { x => for { // lookup mark w/ same url
      marks <- mc.find(d :~ Mark.USR -> x.usrId :~ Mark.URLPRFX -> x.url.binaryPrefix).coll[Mark, Seq]()
      markId = marks.headOption.map(_.id).getOrElse("")
      _ <- c.update(d :~ ID -> x.id :~ TIMEFROM -> x.timeFrom,
                    d :~ "$unset" -> (d :~ "url" -> 1 :~ "uPref" -> 1) :~ "$set" -> {d :~ "markId" -> markId},
                    multi = true)
    } yield () }}
  } yield ()

  /* Indexes with names for this mongo collection: */
  private val indxs: Map[String, Index] =
    Index(USR -> Ascending :: MARKID -> Ascending :: Nil) % s"bin-$USR-1-$MARKID-1" ::
    Index(USR -> Ascending :: ID -> Ascending :: TIMETHRU -> Ascending :: Nil, unique = true) %
      s"bin-$USR-1-$ID-1-$TIMETHRU-1-uniq" ::
    Nil toMap;
  futColl map (_.indexesManager ensure indxs)

  /** Update timeThru on an existing inline note and insert a new one with modified values. */
  def update(usr: UUID,
             id: String,
             pos: InlineNotePosition,
             coord: Option[PageCoord]): Future[InlineNote] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    ct = wr.result[InlineNote].get.copy(pos = pos,
                                        pageCoord = coord,
                                        memeId = None,
                                        timeFrom = now,
                                        timeThru = Long.MaxValue)
    wr <- c insert ct
    _ <- wr failIfError
  } yield ct
}
