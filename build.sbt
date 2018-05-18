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
  "com.google.apis" % "google-api-services-oauth2" % "v2-rev137-1.23.0",
  "io.grpc" % "grpc-netty" % "1.12.0",
  "io.grpc" % "grpc-okhttp" % "1.12.0"
)

fork := true

cancelable in Global := true

herokuFatJar in Compile := Some((assemblyOutputPath in assembly).value)

assemblyMergeStrategy in assembly := {
  case PathList("io", "grpc", xs @ _*) => MergeStrategy.last
  case PathList("io", "netty", xs @ _*) => MergeStrategy.last
  case PathList("javax", "inject", xs @ _*) => MergeStrategy.last
  case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
  case PathList("org", "slf4j", xs @ _*) => MergeStrategy.last
  case PathList("org", "apache", xs @ _*) => MergeStrategy.last
  case PathList("org", "newsclub", xs @ _*) => MergeStrategy.last
  case PathList("com", "google", xs @ _*) => MergeStrategy.last
  case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.last
  case PathList("com", "codahale", xs @ _*) => MergeStrategy.last
  case PathList("com", "yammer", xs @ _*) => MergeStrategy.last
  case PathList("com", "github", xs @ _*) => MergeStrategy.last
  case PathList("com", "kohlschutter", xs @ _*) => MergeStrategy.last
  case "about.html" => MergeStrategy.rename
  case "module-info.class" => MergeStrategy.last
  case "META-INF/io.netty.versions.properties" => MergeStrategy.last
  case "META-INF/ECLIPSEF.RSA" => MergeStrategy.last
  case "META-INF/mailcap" => MergeStrategy.last
  case "META-INF/mimetypes.default" => MergeStrategy.last
  case "plugin.properties" => MergeStrategy.last
  case "log4j.properties" => MergeStrategy.last
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
