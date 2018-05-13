import sbt._

scalaVersion := "2.11.8"
version := "0.1.0-SNAPSHOT"
name := "scala-docker-client"

scalacOptions += "-feature"


resolvers ++= Seq(
  Resolver.mavenLocal,
  Resolver.sonatypeRepo("snapshots"),
  Resolver.sonatypeRepo("releases"),
  Resolver.typesafeRepo("releases"),
  Resolver.typesafeRepo("snapshots"),
  //"softprops-maven" at "http://dl.bintray.com/content/softprops/maven"
  //"Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
  //"spray repo" at "http://repo.spray.io"
)

val logbackVer = "1.0.9"

val workaround = {
  sys.props += "packaging.type" -> "jar"
  ()
}

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.8.0-beta2",
  //"me.lessis" %% "tugboat" % "0.2.0",
  "org.apache.commons" % "commons-compress" % "1.16.1",
  //"org.scalaz.stream" %% "scalaz-stream" % "0.8.5",
  //"com.netaporter" %% "scala-uri" % "0.4.2",
  //"com.typesafe.play" %% "play-json" % "2.3.8",
  //"com.typesafe.play" %% "play-iteratees" % "2.3.8",
  //"com.typesafe.akka" %% "akka-actor" % "2.4-SNAPSHOT",
  //"net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
  //"org.specs2" %% "specs2" % "2.4.2" % "test",
  //"ch.qos.logback" % "logback-core" % logbackVer,
  //"ch.qos.logback" % "logback-classic" % logbackVer,
  //"org.almoehi" %% "reactive-docker" % "0.1-SNAPSHOT"
  "com.github.docker-java" % "docker-java" % "3.1.0-rc-3",
  "javax.annotation" % "javax.annotation-api" % "1.3.2",
  "com.google.firebase" % "firebase-admin" % "6.0.0",
  "com.google.apis" % "google-api-services-oauth2" % "v2-rev137-1.23.0",
  "com.google.cloud" % "google-cloud-firestore" % "0.47.0-beta"
)

fork := true

cancelable in Global := true

