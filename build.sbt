/*
 * Copyright (C) 2017-2018 Hamstoo, Inc. <https://www.hamstoo.com>
 */
import sbt.Credentials
import sbt.Keys.credentials

import scala.io.Source
import scala.sys.process.Process

name := "data-model"

// Note that TravisCI (and as was formerly the case with CircleCI) caches the ~/.ivy2 directory of previous builds,
// so even if you're using a -SNAPSHOT build which will overwrite the artifacts at Artifactory, you'll still have
// to manually delete the cache of whichever project is referencing data-model to get it to pick up the new artifacts.
// The `git rev-parse` command below returns weird branch names, "HEAD" and "undefined", in TravisCI.  More here:
//   https://graysonkoonce.com/getting-the-current-branch-name-during-a-pull-request-in-travis-ci/
version := {
  val gitbranch = Process("git rev-parse --abbrev-ref HEAD").lineStream.head
  val branch = scala.util.Try(sys.env( // the below "false" will never be "true", rather it will be the PR# o/w
    if (sys.env("TRAVIS_PULL_REQUEST") == "false") "TRAVIS_BRANCH" else "TRAVIS_PULL_REQUEST_BRANCH"
  )).getOrElse(gitbranch)
  Source.fromFile("VERSION").getLines find (_ => true) map { l =>
    (if (branch == "master" || branch == "HEAD") {
      if (branch == "HEAD") sLog.value.warn("\u001b[35mBuilding detached `HEAD` as if it were `master`\u001b[0m")
      ""
    } else branch + "-") + l.trim
  } getOrElse "latest"
}
scalaVersion := "2.12.6"

organization := "com.hamstoo"

crossScalaVersions := Seq("2.11.11", scalaVersion.value)

homepage := Some(url("https://github.com/Hamstoo/data-model"))

// this line provide test jar file, that can be accessible by `tests` classifier in dependencies,
// like this:
//   "com.hamstoo" %% "data-model" % "version % "test" classifier "tests"
publishArtifact in (Test, packageBin) := true
val artifactoryHost = "***REMOVED***"
publishTo := Some("Artifactory Realm" at s"http://$artifactoryHost:8081/artifactory/sbt-release-local")
credentials += Credentials("Artifactory Realm", artifactoryHost, "admin", sys.env.getOrElse("ARTIFACTORY_PSW", ""))

scalacOptions in ThisBuild ++= Seq(
  "-feature",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-deprecation")

// resolvers
resolvers ++= Seq(
  Resolver.url("jb-bintray", url("http://dl.bintray.com/jetbrains/sbt-plugins"))(Resolver.ivyStylePatterns),
  "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "sonatype-releases" at "http://oss.sonatype.org/content/repositories/releases",
  "Atlassian Releases" at "https://maven.atlassian.com/public/")

val silhouetteVersion = "5.0.0"
val reactiveMongoVersion = "0.16.0" // moving to previous version 0.12.5, because version 0.12.7 throws IndexNotFound error in tests
val akkaVersion = "2.5.17"

// dependencies
libraryDependencies ++= Seq(
  "com.mohiva" %% "play-silhouette" % silhouetteVersion,
  "com.mohiva" %% "play-silhouette-persistence" % silhouetteVersion,
  "com.github.dwickern" %% "scala-nameof" % "1.0.3" % "provided",
  "joda-time" % "joda-time" % "2.10.1",

  "org.reactivemongo" %% "reactivemongo" % reactiveMongoVersion,
  "org.reactivemongo" %% "reactivemongo-akkastream" % reactiveMongoVersion,

  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

  "org.apache.commons" % "commons-text" % "1.6",
  "com.atlassian.commonmark" % "commonmark" % "0.12.1",
  "org.jsoup" % "jsoup" % "1.11.3",
  "org.scalanlp" %% "breeze" % "1.0-RC2",
  "org.scalanlp" %% "breeze-natives" % "1.0-RC2",
  "org.apache.tika" % "tika-parsers" % "1.18", 
  "org.scalatest" %% "scalatest" % "3.2.0-SNAP1" % Test,
  "com.github.simplyscala" %% "scalatest-embedmongo" % "0.2.4",
  "org.mockito" % "mockito-core" % "2.23.4" % "test",
  "net.sourceforge.htmlunit" % "htmlunit" % "2.33",
  //"org.typelevel" %% "spire" % "0.15.0",
  "com.google.inject" % "guice" % "4.2.2",
  "net.codingwell" %% "scala-guice" % "4.2.1",
  "io.monix" %% "monix-eval" % "3.0.0-8084549"
)

// Pdfclown 0.2.0 is not available in maven so it was added as jar-file dependency
unmanagedBase := baseDirectory.value / "lib"

pomIncludeRepository := { _ => false }

pomExtra :=
  <scm>
    <url>git@github.com:Hamstoo/data-model.git</url>
    <connection>scm:git:git@github.com:Hamstoo/data-model.git</connection>
  </scm>

// there are unfortunate dependencies between the tests in MongoMarksDaoTests, and likely in other places as well
parallelExecution := false
