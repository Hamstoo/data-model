name := "data-model"
organization := "com.hamstoo"
homepage := Some(url("https://github.com/Hamstoo/data-model"))
version := "0.8.0-beta-1"

scalaVersion := "2.11.11"
crossScalaVersions := Seq("2.11.11", "2.11.7")
scalacOptions in ThisBuild ++= Seq("-feature", "-language:postfixOps", "-language:implicitConversions")

lazy val root = project in file(".")

val gitBranch = settingKey[String]("Determines current git branch")
gitBranch := Process("git rev-parse --abbrev-ref HEAD").lines.head
val releaseGitBranch = settingKey[Boolean]("Determines current git branch is release")
releaseGitBranch := gitBranch.value.startsWith("release")
isSnapshot := !releaseGitBranch.value
publishTo := {
  if (releaseGitBranch.value)
    Some("Artifactory Realm" at "http://ec2-54-236-36-52.compute-1.amazonaws.com:8081/artifactory/sbt-release-local")
  else
    Some("Artifactory Realm" at "http://ec2-54-236-36-52.compute-1.amazonaws.com:8081/artifactory/sbt-dev-local;build.timestamp=" + new java.util.Date().getTime)
}
credentials += Credentials("Artifactory Realm", "ec2-54-236-36-52.compute-1.amazonaws.com", "admin", "***REMOVED***")

resolvers ++= Seq(
  "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "sonatype-releases" at "http://oss.sonatype.org/content/repositories/releases",
  "Atlassian Releases" at "https://maven.atlassian.com/public/")

libraryDependencies ++= Seq(
  "com.mohiva" %% "play-silhouette" % "4.0.0",
  "com.mohiva" %% "play-silhouette-persistence" % "4.0.0",
  "org.scala-lang" % "scala-reflect" % "2.13.0-M1",
  "joda-time" % "joda-time" % "2.9.9",
  "org.reactivemongo" %% "reactivemongo" % "0.12.3",
  "org.specs2" %% "specs2-core" % "3.8.9" % "test")

pomIncludeRepository := { _ => false }
pomExtra :=
  <scm>
    <url>git@github.com:Hamstoo/data-model.git</url>
    <connection>scm:git:git@github.com:Hamstoo/data-model.git</connection>
  </scm>
