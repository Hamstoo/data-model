import sbt.Credentials
import sbt.Keys.credentials

import scala.io.Source
import scala.sys.process.Process

name := "data-model"

// We're no longer doing anything with -SNAPSHOT versions.  If you're working in a branch where you want to
// temporarily put -SNAPSHOT in the version file so that your artifacts (may) get overwritten each time you
// push, then knock yourself out.  But note it's unclear that Artifactory, as currently configured, allows
// for the automatic overwriting of such JAR files; and also, circle.yml caches the ~/.ivy2 directory of previous
// builds so you'll probably have to "Rebuild without cache" anyway for the new JAR to take effect.
version := {
  val branch = Process("git rev-parse --abbrev-ref HEAD").lineStream.head
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
