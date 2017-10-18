package com.hamstoo.daos

import java.util.UUID

import com.hamstoo.models.{Highlight, Mark}
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
class MongoHighlightDao(db: Future[DefaultDB]) {

  import com.hamstoo.models.Highlight._
  import com.hamstoo.models.Mark.{TIMEFROM, TIMETHRU}
  import com.hamstoo.utils._
  val logger: Logger = Logger(classOf[MongoHighlightDao])

  private val futColl: Future[BSONCollection] = db map (_ collection "highlights")

  // convert url/uPrefs to markIds
  case class WeeHighlight(usrId: UUID, id: String, timeFrom: Long, url: String)
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
    urled <- {
      implicit val r: BSONDocumentHandler[WeeHighlight] = Macros.handler[WeeHighlight]
      c.find(sel).coll[WeeHighlight, Seq]()
    }
    _ = logger.info(s"Updating ${urled.size} Highlights with markIds (and removing their URLs)")
    _ <- Future.sequence { urled.map { x => for { // lookup mark w/ same url
      marks <- mc.find(d :~ Mark.USER -> x.usrId :~ Mark.URLPRFX -> x.url.binaryPrefix).coll[Mark, Seq]()
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
    Nil toMap;
  
  futColl map (_.indexesManager ensure indxs)

  def create(hl: Highlight): Future[Unit] = for {
    c <- futColl
    wr <- c.insert(hl)
    _ <- wr failIfError
  } yield ()

  def retrieve(usr: UUID, id: String): Future[Option[Highlight]] = for {
    c <- futColl
    mbHl <- c.find(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ POS -> 1).one[Highlight]
  } yield mbHl

  /** Requires `usr` argument so that index can be used for lookup. */
  def retrieveByMarkId(usr: UUID, markId: String): Future[Seq[Highlight]] = for {
    c <- futColl
    seq <- c.find(d :~ USR -> usr :~ MARKID -> markId :~ curnt).coll[Highlight, Seq]()
  } yield seq

  def update(usr: UUID, id: String, pos: Highlight.Position, prv: Highlight.Preview): Future[Highlight] = for {
    c <- futColl
    now = DateTime.now.getMillis
    sel = d :~ USR -> usr :~ ID -> id :~ curnt
    wr <- c.findAndUpdate(sel, d :~ "$set" -> (d :~ TIMETHRU -> now), fetchNewObject = true)
    hl = wr.result[Highlight].get.copy(
      pos = pos,
      preview = prv,
      memeId = None,
      timeFrom = now,
      timeThru = INF_TIME)
    wr <- c.insert(hl)
    _ <- wr failIfError
  } yield hl

  def delete(usr: UUID, id: String): Future[Unit] = for {
    c <- futColl
    wr <- c update(d :~ USR -> usr :~ ID -> id :~ curnt, d :~ "$set" -> (d :~ TIMETHRU -> DateTime.now.getMillis))
    _ <- wr failIfError
  } yield ()
}
