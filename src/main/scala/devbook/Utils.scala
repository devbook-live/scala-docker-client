package DevBook

import DevBook.DockerContext._

import java.io._

import scala.collection.JavaConverters._
import scala.collection.mutable.{ListBuffer, StringBuilder}
import scala.concurrent.{Future, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import scala.language.postfixOps

import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveInputStream, TarArchiveOutputStream}
import org.apache.commons.compress.utils.IOUtils;

import com.github.dockerjava.api.model.{WaitResponse, BuildResponseItem, Event, Frame}
import com.github.dockerjava.core.command.{BuildImageResultCallback, WaitContainerResultCallback, EventsResultCallback, LogContainerResultCallback} 

object Utils {
  val defaultDockerfileContents =
    """
    FROM node:carbon
    WORKDIR /usr/src/app
    COPY package*.json ./
    COPY . .
    """;

  val defaultPackageJSONContents =
    """{
      "name": "defaultName",
      "version": "1.0.0",
      "description": "",
      "author": "First Last <first.last@example.com>",
      "main": "index.js",
      "scripts": {
        "start": "node index.js"
      }
    }"""

  val defaultDockerIgnoreContents =
    """node_modules
    npm-debug.log"""

  case class DockerImageContents(indexJSContents: String, dockerfileContents: String = defaultDockerfileContents, packageJSONContents: String = defaultPackageJSONContents, dockerignoreContents: String = defaultDockerIgnoreContents)

  private def getTarArchiveOutputStream(path: String) = {
    val taos = new TarArchiveOutputStream(new FileOutputStream(path))
    taos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
    taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
    taos.setAddPaxHeadersForNonAsciiNames(true)
    taos
  }

  private def addDirToArchive(outputStream: TarArchiveOutputStream, path: String) {
    val files: Array[File] = (new File(path)).listFiles()
    files.foreach(file => {
      outputStream.putArchiveEntry(new TarArchiveEntry(file, path + File.separator + file.getName()))
      val inputStream = new FileInputStream(file)
      IOUtils.copy(inputStream, outputStream);
      outputStream.closeArchiveEntry();
    })
  }

  def writeTemporaryDirectory(id: String, contents: DockerImageContents): Future[Unit] = {
    val path = s"/tmp/docker-$id/"

    Future {
      val createdDir = new File(path).mkdirs()
      val pwIndexJS = new PrintWriter(path + "index.js")
      val pwDockerfile = new PrintWriter(path + "Dockerfile")
      val pwPackageJSON = new PrintWriter(path + "package.json")
      val pwDockerignore = new PrintWriter(path + ".dockerignore")

      contents match {
        case DockerImageContents(indexJSContents, dockerfileContents, packageJSONContents, dockerignoreContents) =>
          pwIndexJS.write(indexJSContents)
          pwIndexJS.close

          pwDockerfile.write(dockerfileContents)
          pwDockerfile.close

          pwPackageJSON.write(packageJSONContents)
          pwPackageJSON.close

          pwDockerignore.write(dockerignoreContents)
          pwDockerignore.close

          //addDirToArchive(getTarArchiveOutputStream(s"/tmp/docker-image-$id.tar"), s"/tmp/docker-$id")
      }
    }
  }

  private def logEvents = {
    val eventsCallback = new EventsResultCallback() {
      override def onNext(event: Event) {
        println(s"Event: ${event}")
        // Call parent class' onNext method with event
        super.onNext(event)
      }
    }

    Future {
      dockerClient.eventsCmd().exec(eventsCallback).awaitCompletion().close()
    } onComplete {
      case _ => println("Completed event logging")
    }
  }

  private val buildImageCallback = new BuildImageResultCallback() {
    override def onNext(item: BuildResponseItem) = {
      println(s"BuildImageResultCallback: ${item.getStream()}")
      super.onNext(item)
    }
  }

  private val logCallback = new LogContainerResultCallback() {
    val log = new StringBuilder();

    override def onNext(frame: Frame): Unit = {
      log ++= new String(frame.getPayload())
      super.onNext(frame)
    }

    override def toString(): String = {
      log.toString()
    }
  }

  private val waitContainerResultCallback = new WaitContainerResultCallback() {
    override def onNext(waitResponse: WaitResponse) = {
      println("Wait response: " + waitResponse.toString())
    }
  }

  def createImage(id: String): String = {
    println("Creating image")
    Await.result(writeTemporaryDirectory(id, DockerImageContents("console.log('Hello World!');")), timeout)
    println("Created image")
    val baseDir = new java.io.File(s"/tmp/docker-$id/")
    val imageId = dockerClient.buildImageCmd(baseDir).exec(buildImageCallback).awaitImageId()
    println("Built image")
    println(s"Image id: ${imageId}")
    imageId
  }

  def createAndRunContainer(imageId: String): Unit = {
    println("Creating container")
    val container = dockerClient.createContainerCmd(imageId)
      .withCmd("npm", "start")
      .exec()

    dockerClient.startContainerCmd(container.getId()).exec()

    dockerClient.logContainerCmd(container.getId())
      .withStdErr(true)
      .withStdOut(true)
      .withFollowStream(true)
      .withTailAll()
      .exec(logCallback)
      .awaitCompletion()

    dockerClient.waitContainerCmd(container.getId()).exec(waitContainerResultCallback)
    println("Printing log callback...")
    println(logCallback.toString())
  }


}
