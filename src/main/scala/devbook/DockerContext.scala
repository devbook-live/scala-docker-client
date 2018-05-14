package DevBook

import java.io.File
import scala.io.Source

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeUnit._
import scala.concurrent._

import scala.util.Try

import com.github.dockerjava.api._
import com.github.dockerjava.core._

object DockerContext {
  private val serverAddressFile = new File(System.getProperty("user.dir") + "/serverAddress.txt")
  // Try server address file first
  // Then DOCKER_SERVER_ADDRESS environment variable
  // Then fallback on unix socket
  private val serverAddress: String =
    if (serverAddressFile.exists()) {
      val inputFile = Source.fromFile(serverAddressFile)
      val line = inputFile.bufferedReader.readLine
      inputFile.close
      line
    } else if (Try(sys.env("DOCKER_SERVER_ADDRESS")).isSuccess) {
      sys.env("DOCKER_SERVER_ADDRESS")
    } else {
      "unix:///var/run/docker.sock"
    }


  val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
    .withDockerHost(serverAddress)
    .withApiVersion("1.26")
    .build();

  implicit lazy val dockerClient = DockerClientBuilder.getInstance(config).build();
  implicit val timeout = Duration.create(60, SECONDS)
}
