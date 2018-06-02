package DevBook

import DevBook.Utils.synchronizedPrintln

import java.io.{ File, PrintWriter, IOException }
import scala.io.Source

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeUnit._
import scala.concurrent._

import scala.util.{ Try, Success, Failure }

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

  // Create configuration for Docker
  // Use the server address defined above
  // And use 1.26 API version
  var configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
    .withDockerHost(serverAddress)
    .withApiVersion("1.26")

  // We're checking the DOCKER_TLS_VERIFY environment variable
  // and if it exists we'll use TLS verification
  // And then we'll DOCKER_CERT_CA, DOCKER_CERT_CERT, DOCKER_CERT_KEY
  // from the environment and write those out to files
  // and then we'll give that directory to the configuration
  //
  // One thing here is that I'm not handling any exceptions
  // If there's an error opening or writing a file
  // I want the error to propagate up the call stack
  // and stop the program
  if (System.getenv("DOCKER_TLS_VERIFY") != null) {
    configBuilder = configBuilder.withDockerTlsVerify(true)

    val ca_contents =
      Try(sys.env("DOCKER_CERT_CA")) match {
        case Success(ca_contents) =>
          Some(ca_contents)
        case Failure(_) => 
          synchronizedPrintln("Error getting DOCKER_CERT_CA variable")
          None
      }

    val cert_contents =
      Try(sys.env("DOCKER_CERT_CERT")) match {
        case Success(cert_contents) =>
          Some(cert_contents)
        case Failure(_) =>
          synchronizedPrintln("Error getting DOCKER_CERT_CERT variable")
          None
      }

    val key_contents =
      Try(sys.env("DOCKER_CERT_KEY")) match {
        case Success(key_contents) =>
          Some(key_contents)
        case Failure(_) =>
          synchronizedPrintln("Error getting DOCKER_CERT_KEY variable")
          None
      }

    // Make sure none of the environment variables were empty
    // Then just write them all out
    if (!ca_contents.isEmpty && !cert_contents.isEmpty && !key_contents.isEmpty) {
      val currWorkDir = System.getProperty("user.dir")

      val createdDir = new File(s"$currWorkDir/.docker").mkdirs()
      var printWriter = new PrintWriter(s"$currWorkDir/.docker/ca.pem")
      printWriter.write(ca_contents.get)
      if (printWriter.checkError()) throw new IOException()
      printWriter.close

      printWriter = new PrintWriter(s"$currWorkDir/.docker/cert.pem")
      printWriter.write(cert_contents.get)
      if (printWriter.checkError()) throw new IOException()
      printWriter.close

      printWriter = new PrintWriter(s"$currWorkDir/.docker/key.pem")
      printWriter.write(key_contents.get)
      if (printWriter.checkError()) throw new IOException()
      printWriter.close

      // Give the configuration builder the path for the security certificates
      configBuilder = configBuilder.withDockerCertPath(s"$currWorkDir/.docker")
    }
  }

  // Actually build the configuration
  val config = configBuilder.build()

  // Create an instance of the Java DockerClient
  // https://github.com/docker-java/docker-java/wiki
  // lazy means it won't actually be computed until it's needed (Haskell is like this)
  // it's sort of like waiting until the last minute to do something
  //
  // implicit is sort of hard to explain but if a method has an implicit parameter
  // it will look in scope for the variable if it's not given
  // https://alvinalexander.com/scala/scala-implicit-method-arguments-fields-example
  implicit lazy val dockerClient = DockerClientBuilder.getInstance(config).build();
  implicit val timeout = Duration.create(60, SECONDS)
}
