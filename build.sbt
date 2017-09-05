name := "data-model"
organization := "com.hamstoo"
homepage := Some(url("https://github.com/Hamstoo/data-model"))
version := "0.9.8"

scalaVersion := "2.12.3"
crossScalaVersions := Seq("2.11.11", "2.11.7", "2.12.3")
scalacOptions in ThisBuild ++= Seq("-feature", "-language:postfixOps", "-language:implicitConversions", "-deprecation")

lazy val root = project in file(".")

publishTo :=
  Some("Artifactory Realm" at "http://ec2-54-236-36-52.compute-1.amazonaws.com:8081/artifactory/sbt-release-local")

credentials += Credentials(
  "Artifactory Realm",
  "ec2-54-236-36-52.compute-1.amazonaws.com",
  "admin",
  sys.env.getOrElse("ARTIFACTORY_PSW", ""))

resolvers ++= Seq(
  Resolver.url("jb-bintray", url("http://dl.bintray.com/jetbrains/sbt-plugins"))(Resolver.ivyStylePatterns),
  "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "sonatype-releases" at "http://oss.sonatype.org/content/repositories/releases",
  "Atlassian Releases" at "https://maven.atlassian.com/public/")

libraryDependencies ++= Seq(
  "com.mohiva" %% "play-silhouette" % "5.0.0",
  "com.mohiva" %% "play-silhouette-persistence" % "5.0.0",
  "com.github.dwickern" %% "scala-nameof" % "1.0.3" % "provided",
  "joda-time" % "joda-time" % "2.9.9",
  "org.reactivemongo" %% "reactivemongo" % "0.12.5",
  "org.specs2" %% "specs2-core" % "3.8.9" % "test",
  "org.apache.commons" % "commons-text" % "1.1",
  "com.atlassian.commonmark" % "commonmark" % "0.9.0",
  "org.jsoup" % "jsoup" % "1.10.3",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test",
  "com.github.simplyscala" %% "scalatest-embedmongo" % "0.2.4" % "test")

pomIncludeRepository := { _ => false }

pomExtra :=
  <scm>
    <url>git@github.com:Hamstoo/data-model.git</url>
    <connection>scm:git:git@github.com:Hamstoo/data-model.git</connection>
  </scm>