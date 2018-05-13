package DevBook

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeUnit._
import scala.concurrent._

import com.github.dockerjava.api._
import com.github.dockerjava.core._

object DockerContext {
  implicit lazy val dockerClient = DockerClientBuilder.getInstance("unix:///var/run/docker.sock").build();
  implicit val timeout = Duration.create(60, SECONDS)
}
