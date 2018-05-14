package DevBook

import DevBook.DockerContext._
import DevBook.FirebaseService._

import java.io._
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

import scala.collection.JavaConverters._
import scala.collection.concurrent.{Map => ConcurrentMap}
import scala.collection.mutable.{ListBuffer, StringBuilder}

import scala.concurrent.{Future, Await, blocking}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import scala.util.{Try, Success, Failure}
import scala.language.postfixOps

import com.github.dockerjava.api.model.{WaitResponse, BuildResponseItem, Event, Frame}
import com.github.dockerjava.core.command.{BuildImageResultCallback, WaitContainerResultCallback, EventsResultCallback, LogContainerResultCallback} 

import com.google.cloud.firestore.{ListenerRegistration, EventListener, FirestoreException, QuerySnapshot, QueryDocumentSnapshot}

object Utils {
  private[DevBook] val snippetIdToContainerId: ConcurrentMap[String, String] = new ConcurrentHashMap[String, String]().asScala
  var listenerRegistration: ListenerRegistration = null

  val defaultDockerfileContents =
    """
    FROM node:carbon
    WORKDIR /usr/src/app
    COPY package*.json ./
    COPY . .
    """;

  val defaultPackageJSONContents =
    """
    {
      "name": "defaultName",
      "version": "1.0.0",
      "description": "",
      "author": "First Last <first.last@example.com>",
      "main": "index.js",
      "scripts": {
        "start": "node index.js"
      }
    }
    """

  val defaultDockerIgnoreContents =
    """
    node_modules
    npm-debug.log
    """

  case class DockerImageContents(indexJSContents: String, dockerfileContents: String = defaultDockerfileContents, packageJSONContents: String = defaultPackageJSONContents, dockerignoreContents: String = defaultDockerIgnoreContents)

  def writeTemporaryDirectory(id: String, contents: DockerImageContents): Unit = {
    val path = s"/tmp/docker-$id/"

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
    }
  }

  private def logEvents = {
    val eventsCallback = new EventsResultCallback() {
      override def onNext(event: Event) {
        System.out.synchronized {
          println(s"Event: ${event}")
        }
        // Call parent class' onNext method with event
        super.onNext(event)
      }
    }

    Future {
      dockerClient.eventsCmd().exec(eventsCallback).awaitCompletion().close()
    } onComplete {
      case _ => 
        System.out.synchronized {
          println("Completed event logging")
        }
    }
  }

  private val buildImageCallback = new BuildImageResultCallback() {
    override def onNext(item: BuildResponseItem) = {
      println(s"BuildImageResultCallback: ${item.getStream()}")
      super.onNext(item)
    }
  }

  private val alphanumericPattern = "[a-zA-Z0-9]+".r

  private class MyLogContainerResultCallback(snipId: String) extends LogContainerResultCallback {
    private val snippetId = snipId
    val log = new StringBuilder();

    override def onNext(frame: Frame): Unit = {
      val payload = new String(frame.getPayload())
      if (!alphanumericPattern.findFirstIn(payload).isEmpty && !payload.contains("/usr/src/app") && !payload.contains("node")) {
        log.synchronized {
          log ++= payload 
        }

        Future {
          blocking {
            log.synchronized {
              db.collection("snippetOutputs").document(snippetId).set(Map[String, Object]("output" -> log.toString()).asJava)
            }
          }
        } onComplete {
          case Success(_) =>
            System.out.synchronized {
              println(s"Updated output for snippet $snippetId.")
            }
          case Failure(_) =>
            System.out.synchronized {
              println(s"Failed to update output for snippet $snippetId.")
            }
        }
      }

      System.out.synchronized {
        println("Payload: " + payload)
      }

      super.onNext(frame)

    }

    override def toString(): String = {
      log.synchronized {
        log.toString()
      }
    }
  }

  private val waitContainerResultCallback = new WaitContainerResultCallback() {
    override def onNext(waitResponse: WaitResponse) = {
      System.out.synchronized {
        println("Wait response: " + waitResponse.toString())
      }
    }
  }

  def createImage(snippetId: String, indexJSContents: String): (String, String) = {
    System.out.synchronized {
      println("Creating image")
    }
    var imageId: String = null

    // This tells the global ExecutionContext that this is blocking
    // and maybe it should spawn more threads
    // https://stackoverflow.com/a/19682155
    // An ExecutionContext is something that keeps a pool of threads
    // and it grabs tasks from a worker queue and assigns threads to
    // tasks it takes off the queue; it basically allows reuse of
    // threads because thread creation is very expensive
    blocking {
      writeTemporaryDirectory(snippetId, DockerImageContents(indexJSContents))
      val baseDir = new java.io.File(s"/tmp/docker-$snippetId/")
      imageId = dockerClient.buildImageCmd(baseDir).exec(buildImageCallback).awaitImageId()
    }

    System.out.synchronized {
      println(s"Built image with image id ${imageId}")
    }
    (imageId, snippetId)
  }

  def createAndRunContainer(imageId: String, snippetId: String): Unit = {
    System.out.synchronized {
      println("Creating container")
    }
    val container = dockerClient.createContainerCmd(imageId)
      .withCmd("npm", "start")
      .exec()
    
    val containerId = container.getId()

    snippetIdToContainerId.putIfAbsent(snippetId, containerId)

    dockerClient.startContainerCmd(containerId).exec()

    // Separate task to kill container
    Future {
      blocking {
        // Wait for 15 seconds
        Thread.sleep(15 * 1000)
        // Forcefully remove the container and then remove the image
        Try(dockerClient.removeContainerCmd(containerId).withForce(true).exec()) match {
          case Success(_) => 
            System.out.synchronized {
              println(s"Successfully removed container $containerId")
            }
          case Failure(_) =>
            System.out.synchronized {
              println(s"Failed to remove container $containerId")
            }
        }
        /*
        Try(dockerClient.removeImageCmd(imageId).exec()) match {
          case Success(_) => 
            System.out.synchronized {
              println(s"Successfully removed image $imageId")
            }
          case Failure(_) =>
            System.out.synchronized {
              println(s"Failed to remove image $imageId")
            }
        }
        */
      }
    } onComplete {
      case _ => ()
    }

    blocking {
      dockerClient.logContainerCmd(containerId)
        .withStdErr(true)
        .withStdOut(true)
        .withFollowStream(true)
        .withTailAll()
        .exec(new MyLogContainerResultCallback(snippetId))
        .awaitCompletion()
        .close()
    }

    dockerClient.waitContainerCmd(containerId).exec(waitContainerResultCallback)
  }

  def createImageAndRunContainer(id: String, indexJSContents: String): Unit = {
    Future {
      createImage(id, indexJSContents) match {
        case (imageId, snippetId) =>
          createAndRunContainer(imageId, snippetId)
      }
    } onComplete {
      case Success(_) => 
        System.out.synchronized {
          println(s"Finished running $id.")
        }
      case Failure(err) => 
        System.out.synchronized {
          println(s"Error running $id.")
          println(s"Error: $err")
        }
    }
  }

  val querySnapshotCallback =
    (doc: QueryDocumentSnapshot) => {
      val snippetId = doc.getId()
      // Assuming a snippet has some text
      // Check if we've seen this snippet before
      // If not, create the image first and then run container
      // Otherwise just restart the container
      Option(doc.get("text")).foreach(indexJSContents => {
        // So concurrent.Map.get() returns an Option so I have to do something to get the value
        // Option.getOrElse() is one of the things I can do to get the value
        val containerIdOpt = snippetIdToContainerId.get(snippetId)
        if (!containerIdOpt.isEmpty) {
          // So I'm using get() here which is normally an unsafe operation (because the Option
          // could be None) but I just checked so I know it's not None
          val containerId = containerIdOpt.get()
          Try(dockerClient.removeContainerCmd(containerId).withForce(true).exec()) match {
            case Success(_) =>
              System.out.synchronized {
                println(s"Successfully removed container $containerId")
              }
            case Failure(_) =>
              System.out.synchronized {
                println(s"Failed to remove container $containerId")
              }
          }
        }

        createImageAndRunContainer(snippetId, indexJSContents.toString)
      })
    }

  // Lot of code here for the event listener callback
  // But basically it's mostly error handling
  val eventListenerCallback =
    new EventListener[QuerySnapshot]() {
      override def onEvent(snapshots: QuerySnapshot, e: FirestoreException) = {
        // Option is a special type in Scala
        // It is a 1-element collection which
        // means that foreach, map, etc. work
        // And they only do anything if there's
        // some value
        //
        // Option(null) == None
        // Option(5) == Some(5)
        Option(e) match {
          case Some(e) =>
            System.err.synchronized {
              System.err.println(s"Listen failed: $e")
            }
          case None =>
            System.out.synchronized {
              println(s"Received query snapshot of size ${snapshots.size}");
            }
            snapshots.forEach(new Consumer[QueryDocumentSnapshot]() {
              override def accept(arg: QueryDocumentSnapshot) = {
                querySnapshotCallback.apply(arg)
              }
            })
        }
      }
    }

  def snippetsSubscribe() = {
      listenerRegistration = db.collection("snippets").whereEqualTo("running", true)
        .addSnapshotListener(eventListenerCallback)
  }


}
