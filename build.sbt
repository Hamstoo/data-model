name := "data-model"
organization := "com.hamstoo"
homepage := Some(url("https://github.com/Hamstoo/data-model"))
version := "feature.114-alpha.2"

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
  if (releaseGitBranch.value) Some("Artifactory Realm" at
    "http://ec2-54-236-36-52.compute-1.amazonaws.com:8081/artifactory/sbt-release-local")
  else Some("Artifactory Realm" at
    "http://ec2-54-236-36-52.compute-1.amazonaws.com:8081/artifactory/sbt-dev-local;build.timestamp=" +
    new java.util.Date().getTime)
}
credentials += Credentials(
  "Artifactory Realm",
  "ec2-54-236-36-52.compute-1.amazonaws.com",
  "admin",
  sys.env.getOrElse("ARTIFACTORY_PSW", ""))

resolvers ++= Seq(
  "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "sonatype-releases" at "http://oss.sonatype.org/content/repositories/releases",
  "Atlassian Releases" at "https://maven.atlassian.com/public/")

libraryDependencies ++= Seq(
  "com.mohiva" %% "play-silhouette" % "5.0.0-RC2",
  "com.mohiva" %% "play-silhouette-persistence" % "5.0.0-RC2",
  "com.github.dwickern" %% "scala-nameof" % "1.0.3" % "provided",
  "joda-time" % "joda-time" % "2.9.9",
  "org.reactivemongo" %% "reactivemongo" % "0.12.5",
  "org.specs2" %% "specs2-core" % "3.8.9" % "test",
  "io.spray" %% "spray-caching" % "1.3.4",
  "org.apache.commons" % "commons-text" % "1.1",
  "com.atlassian.commonmark" % "commonmark" % "0.9.0",
  "org.jsoup" % "jsoup" % "1.10.3")

pomIncludeRepository := { _ => false }
pomExtra :=
  <scm>
    <url>git@github.com:Hamstoo/data-model.git</url>
    <connection>scm:git:git@github.com:Hamstoo/data-model.git</connection>
  </scm>
