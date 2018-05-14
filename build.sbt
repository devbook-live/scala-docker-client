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
)

val logbackVer = "1.0.9"

val workaround = {
  sys.props += "packaging.type" -> "jar"
  ()
}

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.8.0-beta2",
  "com.github.docker-java" % "docker-java" % "3.1.0-rc-3",
  "javax.annotation" % "javax.annotation-api" % "1.3.2",
  "com.google.firebase" % "firebase-admin" % "6.0.0",
  "com.google.apis" % "google-api-services-oauth2" % "v2-rev137-1.23.0"
)

fork := true

cancelable in Global := true

herokuFatJar in Compile := Some((assemblyOutputPath in assembly).value)
