import sbt._

object Dependencies {

  final val dataModelResolvers = Seq(
    Resolver.url("jb-bintray", url("http://dl.bintray.com/jetbrains/sbt-plugins"))(Resolver.ivyStylePatterns),
    "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
    "sonatype-releases" at "http://oss.sonatype.org/content/repositories/releases",
    "Atlassian Releases" at "https://maven.atlassian.com/public/")

  val reactiveMongoVersion = "0.12.5"
  val akkaVersion = "2.5.9"

  final val dataModelDep = Seq(
    "com.mohiva" %% "play-silhouette" % "5.0.0",
    "com.mohiva" %% "play-silhouette-persistence" % "5.0.0",
    "com.github.dwickern" %% "scala-nameof" % "1.0.3" % "provided",
    "joda-time" % "joda-time" % "2.9.9",

    // moving to previous version 0.12.5, because version: 0.12.7 throw IndexNotFound error in tests
    "org.reactivemongo" %% "reactivemongo" % reactiveMongoVersion,
    "org.reactivemongo" %% "reactivemongo-akkastream" % reactiveMongoVersion,

    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

    "org.apache.commons" % "commons-text" % "1.1",
    "com.atlassian.commonmark" % "commonmark" % "0.9.0",
    "org.jsoup" % "jsoup" % "1.10.3",
    "org.scalanlp" %% "breeze" % "0.13.1",
    "org.scalanlp" %% "breeze-natives" % "0.13.1",
    "org.apache.tika" % "tika-parsers" % "1.16",
    "org.scalatest" %% "scalatest" % "3.0.4",
    "com.github.simplyscala" %% "scalatest-embedmongo" % "0.2.4",
    "org.mockito" % "mockito-core" % "2.10.0" % "test",
    "net.sourceforge.htmlunit" % "htmlunit" % "2.28",
    "com.google.inject" % "guice" % "4.2.0",
    "net.codingwell" %% "scala-guice" % "4.1.1",
    "org.typelevel" %% "spire" % "0.15.0"
  )
}
