package com.hamstoo.utils

import java.util.UUID

import com.hamstoo.models.{Mark, MarkData}

trait DataInfo {

  lazy val vectorsLink = "http://localhost:5000"

  lazy val idfsResource = "idfs/text8.json.zip"

  lazy val link = "mongodb://localhost:12345"

  lazy val dbName = "hamstoo"

  val userId = UUID.randomUUID()

  val mdA = MarkData("a subject", Some("http://a.com"), Some(3.0), Some(Set("atag")), Some("a comment"))
  val mdB = MarkData("b subject", Some("http://b.com"), Some(4.0), Some(Set("btag")), Some("b comment"))
  val mA = Mark(userId, mark = mdA, pubRepr = Some("aPubRepr"), privRepr = Some("aPrivRepr"))
  val mB = Mark(userId, mark = mdB, pubRepr = Some("bPubRepr"), privRepr = Some("bPrivRepr"))

  val crazyUrl = "https://translate.google.com.ua/#ru/en/%D0%94%D0%BE%D0%B1%D1%80%D1%8B%D0%B9%20%D0%B4%D0%B5%D0%BD%D1%8C!%0A%D0%9C%D1%8B%20%D0%BE%D0%B7%D0%BD%D0%B0%D0%BA%D0%BE%D0%BC%D0%B8%D0%BB%D0%B8%D1%81%D1%8C%20%D1%81%20%D0%B2%D0%B0%D1%88%D0%B8%D0%BC%D0%B8%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F%D0%BC%D0%B8%20%D0%BD%D0%B0%20%D0%BD%D0%B0%D1%88%20%D0%BE%D1%82%D0%B7%D1%8B%D0%B2%20%20%D0%B8%20%D0%BE%D1%87%D0%B5%D0%BD%D1%8C%20%D0%BE%D0%B3%D0%BE%D1%80%D1%87%D0%B5%D0%BD%D1%8B%20%D1%82%D0%B5%D0%BC%2C%20%D1%87%D1%82%D0%BE%20%D0%B2%D0%BB%D0%B0%D0%B4%D0%B5%D0%BB%D0%B5%D1%86%20%20Bronzino%20Apartments%20%D0%BD%D0%B5%20%D1%82%D0%BE%D0%BB%D1%8C%D0%BA%D0%BE%20%D0%B0%D0%B3%D1%80%D0%B5%D1%81%D1%81%D0%B8%D0%B2%D0%B5%D0%BD%20%D0%B8%20%D0%BD%D0%B5%D1%83%D1%80%D0%B0%D0%B2%D0%BD%D0%BE%D0%B2%D0%B5%D1%88%D0%B5%D0%BD%2C%20%D0%BD%D0%BE%20%D0%B8%20%D0%BB%D0%B6%D0%B8%D0%B2.%20%D0%98%20%D0%BF%D0%BE%D0%BB%D1%83%D1%87%D0%B5%D0%BD%D0%BD%D1%8B%D0%B5%20%D0%BE%D1%82%20%D0%B2%D0%B0%D1%81%20%D0%B2%D0%BE%D0%B7%D1%80%D0%B0%D0%B6%D0%B5%D0%BD%D0%B8%D1%8F"


}
