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
  val branch = if (!Seq("HEAD", "undefined").contains(gitbranch)) gitbranch else // will fail w/ detached head locally
    sys.env(if (sys.env("TRAVIS_PULL_REQUEST") == "true") "TRAVIS_PULL_REQUEST_BRANCH" else "TRAVIS_BRANCH")
  Source.fromFile("VERSION").getLines find (_ => true) map { l =>
    (if (branch == "master") "" else branch + "-") + l.trim
  } getOrElse "latest"
}
scalaVersion := "2.12.3"

organization := "com.hamstoo"

crossScalaVersions := Seq("2.11.11", "2.11.7", "2.12.3")

homepage := Some(url("https://github.com/Hamstoo/data-model"))

// this line provide test jar file, that can be accessible by `tests` classifier in dependencies,
// like this:
//   "com.hamstoo" %% "data-model" % "version % "test" classifier "tests"
publishArtifact in (Test, packageBin) := true
val artifactoryHost = "***REMOVED***"
publishTo := Some("Artifactory Realm" at s"http://$artifactoryHost:8081/artifactory/sbt-release-local")
credentials += Credentials(
  "Artifactory Realm",
  artifactoryHost,
  "admin",
  sys.env.getOrElse("ARTIFACTORY_PSW", ""))

scalacOptions in ThisBuild ++= Seq(
  "-feature",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-deprecation")

// resolvers
resolvers ++= Dependencies.dataModelResolvers

// dependencies
libraryDependencies ++= Dependencies.dataModelDep

pomIncludeRepository := { _ => false }

pomExtra :=
  <scm>
    <url>git@github.com:Hamstoo/data-model.git</url>
    <connection>scm:git:git@github.com:Hamstoo/data-model.git</connection>
  </scm>

// there are unfortunate dependencies between the tests in MongoMarksDaoTests, and likely in other places as well
parallelExecution := false
