package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{InlineNote, Mark, PageCoord}
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
class MongoInlineNoteDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.InlineNote._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}
  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoInlineNoteDao])

  private val futColl: Future[BSONCollection] = db map (_ collection "comments")

  // convert url/uPrefs to markIds
  case class WeeNote(usrId: UUID, id: String, timeFrom: Long, url: String)
  private val marksColl: Future[BSONCollection] = db map (_ collection "entries")
  for {
    c <- futColl
    mc <- marksColl

    oldIdx = s"bin-$USR-1-uPref-1"
    _ = logger.info(s"Dropping index $oldIdx")
    nDropped <- c.indexesManager.drop(oldIdx).recover { case _: DefaultBSONCommandError => 0 }
    _ = logger.info(s"Dropped $nDropped index(es)")

    exst = d :~ "$exists" -> true
    sel = d :~ "$or" -> BSONArray(d :~ "url" -> exst, d :~ "uPref" -> exst)
    // pjn = d :~ USR -> 1 :~ ID -> 1 :~ TIMEFROM -> 1 :~ "url" -> 1 // unnecessary b/c WeeNote is a subset
    urled <- {
      implicit val r: BSONDocumentHandler[WeeNote] = Macros.handler[WeeNote]
      c.find(sel).coll[WeeNote, Seq]()
    }
    _ = logger.info(s"Updating ${urled.size} InlineNotes with markIds (and removing their URLs)")
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

  def create(ct: InlineNote): Future[Unit] = for {
    c <- futColl
    wr <- c insert ct
    _ <- wr failIfError
  } yield ()

  def retrieve(usr: UUID, id: String): Future[Option[InlineNote]] = for {
    c <- futColl
    optCt <- (c find d :~ USR -> usr :~ ID -> id :~ curnt projection d :~ POS -> 1).one[InlineNote]
  } yield optCt

  /** Requires `usr` argument so that index can be used for lookup. */
  def retrieveByMarkId(usr: UUID, markId: String): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[InlineNote, Seq]()
  } yield seq

  /*def retrieveSortedByPageCoord(url: String, usr: UUID): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d :~ USR -> usr :~ UPREF -> url.binaryPrefix :~ curnt).coll[Comment, Seq]()
  } yield seq filter (_.url == url) sortWith { case (a, b) => PageCoord.sortWith(a.pageCoord, b.pageCoord) }*/

  // TODO: merge w/ HighlightDao?????



  /** Update timeThru on an existing inline note and insert a new one with modified values. */
  def update(usr: UUID, id: String, pos: InlineNote.Position, coord: Option[PageCoord]): Future[InlineNote] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c findAndUpdate(sel, d :~ "$set" -> (d :~ TILL -> now), fetchNewObject = true)
    ct = wr.result[InlineNote].get.copy(pos = pos,
                                        pageCoord = coord,
                                        memeId = None,
                                        timeFrom = now,
                                        timeThru = Long.MaxValue)
    wr <- c insert ct
    _ <- wr failIfError
  } yield ct

  def delete(usr: UUID, id: String): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()

  /**
    * Be carefull, expensive operation.
    * @return
    */
  def retrieveAll(): Future[Seq[InlineNote]] = for {
    c <- futColl
    seq <- (c find d).coll[InlineNote, Seq]()
  } yield seq
}
