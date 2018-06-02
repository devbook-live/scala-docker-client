package DevBook

import DevBook.DockerContext._
import DevBook.FirebaseService._
import DevBook.Callbacks._

import java.io._
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._
import scala.collection.concurrent.{ Map => ConcurrentMap }
import scala.collection.mutable.{ ListBuffer, StringBuilder }

import scala.concurrent.{ Future, Await, blocking }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import scala.util.{ Try, Success, Failure }
import scala.language.postfixOps

import com.google.cloud.firestore.{ ListenerRegistration, EventListener, FirestoreException, QuerySnapshot, QueryDocumentSnapshot }

object Utils {
  // Thread-safe hashtable that maps from snippetId -> containerId
  private[DevBook] val snippetIdToContainerId: ConcurrentMap[String, String] = new ConcurrentHashMap[String, String]().asScala

  // Represents a listener that can be removed via
  // listenerRegistration.remove()
  private var listenerRegistration: ListenerRegistration = null

  // Basically a better way to build a String
  // since Strings in Java are immutable
  private[DevBook] val imageIdHolder = new StringBuilder()

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

  // Regular expression pattern
  val alphanumericPattern = "[a-zA-Z0-9]+".r

  // Scala is statically typed so you can't just dynamically create some object with the properties you want
  // A case class is very similar to what you'd get from an object literal; it's just strongly and statically typed
  // And the keys are predefined in a case class
  case class DockerImageContents(indexJSContents: String, dockerfileContents: String = defaultDockerfileContents, packageJSONContents: String = defaultPackageJSONContents, dockerignoreContents: String = defaultDockerIgnoreContents)

  // The API documentation for println makes no guarantee
  // of thread safety so I'm synchronizing on it
  // Basically when the synchronizedPrintln method is called
  // the other threads have to wait in line while a thread is
  // printing something out
  def synchronizedPrintln(output: String) = {
    System.out.synchronized {
      System.out.println(output)
    }
  }

  def writeTemporaryDirectory(id: String, contents: DockerImageContents): String = {
    val path = s"/tmp/docker-$id-${scala.util.Random.alphanumeric.take(10).mkString}/"

    // Create the directory
    val createdDir = new File(path).mkdirs()
    // Open file handles for each file we want to write to
    val pwIndexJS = new PrintWriter(path + "index.js")
    val pwDockerfile = new PrintWriter(path + "Dockerfile")
    val pwPackageJSON = new PrintWriter(path + "package.json")
    val pwDockerignore = new PrintWriter(path + ".dockerignore")

    // This is the power of case classes
    // https://www.artima.com/pins1ed/case-classes-and-pattern-matching.html
    //
    // Case classes are Scala's way to allow pattern matching on objects without
    // requiring a large amount of boilerplate. In the common case, all you need
    // to do is add a single case keyword to each class that you want to be pattern matchable.
    //
    // Pattern matching is like object destructuring and switch but much much more powerful
    // 
    // Here we're basically just writing all the stuff to the temporary files
    // And then closing the file handles/descriptors
    // https://en.wikipedia.org/wiki/File_descriptor
    contents match {
      case DockerImageContents(indexJSContents, dockerfileContents, packageJSONContents, dockerignoreContents) =>
        val padding1 = new StringBuilder();
        val padding2 = new StringBuilder();

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
    
    // Implicit return
    // In pure functional languages there is no return keyword
    path
  }

  def createImage(snippetId: String, indexJSContents: String): String = {
    synchronizedPrintln("Creating image")
    var imageId: String = null

    // This tells the global ExecutionContext that this is blocking
    // and maybe it should spawn more threads
    // https://stackoverflow.com/a/19682155
    //
    // An ExecutionContext is something that keeps a pool of threads
    // and it grabs tasks from a worker queue and assigns threads to
    // tasks it takes off the queue; it basically allows reuse of
    // threads because thread creation is very expensive
    blocking {
      // Clear the imageId since we're making a new image
      imageIdHolder.synchronized {
        imageIdHolder.clear()
      }

      val path = writeTemporaryDirectory(snippetId, DockerImageContents(indexJSContents))
      // File is basically a representation of a path or file
      val baseDir = new File(path)
      // Tell Docker to build the actual image based on the given path
      dockerClient.buildImageCmd(baseDir).exec(buildImageCallback).awaitImageId()

      // So here we're doing some special stuff
      // We're grabbing the image name off the output from building the image
      // This syntax is very common in what's called the producer-consumer problem
      //
      // https://en.wikipedia.org/wiki/Producer%E2%80%93consumer_problem
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
  
  def removeContainer(snippetId: String, containerId: String) = {
    // As stated in another file, Try is just a way to deal with Java's exceptions
    // in a more functional way using pattern matching
    Try(dockerClient.removeContainerCmd(containerId).withForce(true).exec()) match {
      case Success(_) => 
        // Remove snippetId->container from hashtable
        snippetIdToContainerId.remove(snippetId)
        synchronizedPrintln(s"Successfully removed container $containerId")
      case Failure(_) =>
        synchronizedPrintln(s"Failed to remove container $containerId")
    }
  }

  def createAndRunContainer(imageId: String, snippetId: String): Unit = {
    synchronizedPrintln("Creating container")
    // Create the container
    val container = dockerClient.createContainerCmd(imageId)
      .withCmd("npm", "start")
      .exec()
    
    // Get the container ID
    val containerId = container.getId()

    // Add snippetId -> containerId to hashtable
    snippetIdToContainerId.put(snippetId, containerId)

    // Create a separate task to kill container
    Future {
      blocking {
        synchronizedPrintln("Starting sleep for kill")
        // Wait for 10 seconds
        Thread.sleep(10 * 1000)
        synchronizedPrintln("Starting kill")
        // Forcefully remove the container
        removeContainer(snippetId, containerId)
        // Remove the image
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

    // Start container
    dockerClient.startContainerCmd(containerId).exec()

    // Log the container's output
    // The really important thing here is
    // the MyLogContainerResultCallback
    //
    // So basically it will call that callback
    // when it gets information from Docker
    // about the container
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
    // Create a new task for creating the image and running the container
    // So you can think of each Future as a task, and each task is added to a task queue
    // and tasks are assigned to threads in a thread pool
    Future {
      val imageId = createImage(snippetId, indexJSContents)
      createAndRunContainer(imageId, snippetId)
    } onComplete {
      case Success(_) => 
        // We succesfully created the image and container
        setRunningFalseFuture(snippetId)
        synchronizedPrintln(s"Finished running $snippetId.")
      case Failure(err) => 
        // Something went wrong in creating image and container
        setRunningFalseFuture(snippetId)
        System.out.synchronized {
          println(s"Error running $snippetId.")
          println(s"Error: $err")
        }

        // Here we set the output to the error in Firestore in a new task
        // I'm creating a new task because this operation takes time
        // (think async operation)
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

  // Subscribe to Firestore
  def snippetsSubscribe() = {
      listenerRegistration = db.collection("snippets").whereEqualTo("running", true)
        .addSnapshotListener(eventListenerCallback)
  }
}
