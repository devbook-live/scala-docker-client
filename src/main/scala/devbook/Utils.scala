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
  // Thread-safe hashtable that maps from snippetId -> containerId
  private[DevBook] val snippetIdToContainerId: ConcurrentMap[String, String] = new ConcurrentHashMap[String, String]().asScala

  private var listenerRegistration: ListenerRegistration = null

  private val imageIdHolder = new StringBuilder()

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

  val alphanumericPattern = "[a-zA-Z0-9]+".r

  // Scala is statically typed so you can't just dynamically create some object with the properties you want
  // A case class is very similar to what you'd get from an object literal; it's just strongly and statically typed
  // And the keys are predefined in a case class
  case class DockerImageContents(indexJSContents: String, dockerfileContents: String = defaultDockerfileContents, packageJSONContents: String = defaultPackageJSONContents, dockerignoreContents: String = defaultDockerIgnoreContents)

  def synchronizedPrintln(output: String) = {
    System.out.synchronized {
      System.out.println(output)
    }
  }

  def writeTemporaryDirectory(id: String, contents: DockerImageContents): String = {
    val path = s"/tmp/docker-$id-${scala.util.Random.alphanumeric.take(10).mkString}/"

    val createdDir = new File(path).mkdirs()
    val pwIndexJS = new PrintWriter(path + "index.js")
    val pwDockerfile = new PrintWriter(path + "Dockerfile")
    val pwPackageJSON = new PrintWriter(path + "package.json")
    val pwDockerignore = new PrintWriter(path + ".dockerignore")

    contents match {
      case DockerImageContents(indexJSContents, dockerfileContents, packageJSONContents, dockerignoreContents) =>
        val padding1 = new StringBuilder();
        val padding2 = new StringBuilder();

        //for (_ <- 1 to 5) padding1 ++= " /* " + scala.util.Random.alphanumeric.take(20).mkString + " */ \n"
        padding1 ++= "function thisRandomRandomRandomFunc() { "
        padding1 ++= scala.util.Random.nextInt().toString() + " + " + scala.util.Random.nextInt().toString()
        padding1 ++= " } \n\n"

        for (_ <- 1 to 5) padding2 ++= " /* " + scala.util.Random.alphanumeric.take(20).mkString + " */ \n"

        val newIndexJSContents: String = padding1.toString() + indexJSContents + padding2.toString()
        println("Contents")
        println(newIndexJSContents)

        pwIndexJS.write(newIndexJSContents)
        pwIndexJS.close

        pwDockerfile.write(dockerfileContents.replace("/usr/src/app", path))
        pwDockerfile.close

        pwPackageJSON.write(packageJSONContents)
        pwPackageJSON.close

        pwDockerignore.write(dockerignoreContents)
        pwDockerignore.close
    }
    
    path
  }

  private val buildImageCallback = new BuildImageResultCallback() {
    override def onNext(item: BuildResponseItem) = {
      val payload = item.getStream()
      if (payload.contains("Successfully built")) {
        imageIdHolder.synchronized {
          imageIdHolder ++= payload.substring(19)
          imageIdHolder.notifyAll()
        }
      }
      synchronizedPrintln(s"BuildImageResultCallback: ${payload}")
      super.onNext(item)
    }
  }

  // We're using a Java API and Java unlike Scala is not functional,
  // so the way it works is when you want a callback, you define an interface or abstract class
  // and the consumer of the API overrides the function defined in the interface or abstract class
  private class MyLogContainerResultCallback(snippetId: String) extends LogContainerResultCallback {
    // Basically an efficient way to build a String
    val log = new StringBuilder()

    // We get the payload from the frame
    // If the payload contains alphanumeric chars
    // and doesn't have /usr/src/app or node in it
    // we add the payload to the log and
    // we send the log to Firestore
    // in snippetOutputs
    //
    // This is ONLY giving us the output from the
    // container
    override def onNext(frame: Frame): Unit = {
      val payload = new String(frame.getPayload())
      if (!payload.contains("/usr/src/app") && !payload.contains("node")) {
        // I'm synchronizing on the log because the
        // Java Virtual Machine has a happens-before relationship
        // that guarantees that if I synchronize on something and
        // it was synchronized before and written on, I will see
        // that write in memory; I was taught to think of it
        // as a cache flush where all CPUs flush their caches
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
            synchronizedPrintln(s"Updated output for snippet $snippetId.")
          case Failure(_) =>
            synchronizedPrintln(s"Failed to update output for snippet $snippetId.")
        }
      }

      synchronizedPrintln("Payload: " + payload)
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
      synchronizedPrintln("Wait response: " + waitResponse.toString())
    }
  }

  def createImage(snippetId: String, indexJSContents: String): String = {
    synchronizedPrintln("Creating image")
    var imageId: String = null

    // This tells the global ExecutionContext that this is blocking
    // and maybe it should spawn more threads
    // https://stackoverflow.com/a/19682155
    // An ExecutionContext is something that keeps a pool of threads
    // and it grabs tasks from a worker queue and assigns threads to
    // tasks it takes off the queue; it basically allows reuse of
    // threads because thread creation is very expensive
    blocking {
      imageIdHolder.synchronized {
        imageIdHolder.clear()
      }

      val path = writeTemporaryDirectory(snippetId, DockerImageContents(indexJSContents))
      //val baseDir = new java.io.File(s"/tmp/docker-$snippetId/")
      val baseDir = new java.io.File(path)
      imageId = dockerClient.buildImageCmd(baseDir).exec(buildImageCallback).awaitImageId()

      imageIdHolder.synchronized {
        while (imageIdHolder.isEmpty) {
          imageIdHolder.wait()
        }
        imageId = imageIdHolder.toString().trim()
      }
    }

    synchronizedPrintln(s"Built image with image id ${imageId}")
    // Last line in a function is the return value
    // Using "return" keyword is bad as it isn't referentially transparent
    imageId
  }

  def createAndRunContainer(imageId: String, snippetId: String): Unit = {
    synchronizedPrintln("Creating container")
    // Create the container
    val container = dockerClient.createContainerCmd(imageId)
      .withCmd("npm", "start")
      .exec()
    
    val containerId = container.getId()

    // Add snippetId -> containerId to hashtable
    snippetIdToContainerId.put(snippetId, containerId)

    // Start container
    dockerClient.startContainerCmd(containerId).exec()

    // Separate task to kill container
    Future {
      blocking {
        // Wait for 15 seconds
        Thread.sleep(5 * 1000)
        // Forcefully remove the container and then remove the image
        Try(dockerClient.removeContainerCmd(containerId).withForce(true).exec()) match {
          case Success(_) => 
            // Remove imageId->container from hashtable
            snippetIdToContainerId.remove(imageId)
            synchronizedPrintln(s"Successfully removed container $containerId")
          case Failure(_) =>
            synchronizedPrintln(s"Failed to remove container $containerId")
        }
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
      }
    } onComplete {
      // I don't care about the result
      // Like somePromise.then(() => {})
      case _ => ()
    }

    // Log the container's output
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

    // Execute this callback when container ends
    dockerClient.waitContainerCmd(containerId).exec(waitContainerResultCallback)
  }


  def setRunningFalseFuture(snippetId: String) = {
    Future {
      // Set running to false once the snippet is done running
      // A little note here: I'm doing something called casting
      // Casting forcefully changes the type of an object
      // It can be dangerous but basically here it wants
      // an Object (Object is AnyRef in Scala), and false is
      // actually a Boolean
      db.collection("snippets").document(snippetId).update(Map[String, Object]("running" -> false.asInstanceOf[AnyRef]).asJava)
    } onComplete {
      case Success(_) =>
        synchronizedPrintln(s"Set running to false for snippet with snippet id $snippetId.")
      case Failure(_) =>
        synchronizedPrintln(s"Failed to set running to false for snippet with snippet id $snippetId.")
    }
  }

  def createImageAndRunContainer(snippetId: String, indexJSContents: String): Unit = {
    Future {
      val imageId = createImage(snippetId, indexJSContents)
      createAndRunContainer(imageId, snippetId)
    } onComplete {
      case Success(_) => 
        setRunningFalseFuture(snippetId)
        synchronizedPrintln(s"Finished running $snippetId.")
      case Failure(err) => 
        setRunningFalseFuture(snippetId)
        System.out.synchronized {
          println(s"Error running $snippetId.")
          println(s"Error: $err")
        }

        Future {
          blocking {
            db.collection("snippetOutputs").document(snippetId).set(Map[String, Object]("output" -> err.toString()).asJava)
          }
        } onComplete {
          case Success(_) =>
            synchronizedPrintln(s"Updated output for snippet $snippetId.")
          case Failure(_) =>
            synchronizedPrintln(s"Failed to update output for snippet $snippetId.")
        }
    }
  }

  val querySnapshotCallback =
    (doc: QueryDocumentSnapshot) => {
      val snippetId = doc.getId()
      // Assuming a snippet has some text
      // Check if we've seen this snippet before
      // If so, remove the container
      // Either way, create the image first and then run container
      Option(doc.get("text")).foreach(indexJSContents => {
        synchronizedPrintln(s"Got text: ${indexJSContents}")
        // So concurrent.Map.get() returns an Option so I have to do something to get the value
        // Option.getOrElse() is one of the things I can do to get the value
        val containerIdOpt: Option[String] = snippetIdToContainerId.get(snippetId)
        if (!containerIdOpt.isEmpty) {
          val containerId: String = containerIdOpt.getOrElse(null)
          Try(dockerClient.removeContainerCmd(containerId).withForce(true).exec()) match {
            case Success(_) =>
              synchronizedPrintln(s"Successfully removed container $containerId")
            case Failure(_) =>
              synchronizedPrintln(s"Failed to remove container $containerId")
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
            synchronizedPrintln(s"Received query snapshot of size ${snapshots.size}");
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
