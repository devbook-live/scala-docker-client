package DevBook

import DevBook.FirebaseService.db
import DevBook.DockerContext.dockerClient
import DevBook.Utils.{imageIdHolder, snippetIdToContainerId, synchronizedPrintln, createImageAndRunContainer}

import java.util.function.Consumer

import scala.util.{Try, Success, Failure}
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Await, blocking}
import scala.concurrent.ExecutionContext.Implicits.global

import com.github.dockerjava.api.model.{WaitResponse, BuildResponseItem, Event, Frame}
import com.github.dockerjava.core.command.{BuildImageResultCallback, WaitContainerResultCallback, EventsResultCallback, LogContainerResultCallback} 

import com.google.cloud.firestore.{ListenerRegistration, EventListener, FirestoreException, QuerySnapshot, QueryDocumentSnapshot}

object Callbacks {
  // This callback runs when the image is built
  //
  // Using new like this and overriding is called an anonymous subclass
  // http://software.danielwatrous.com/override-java-methods-on-instantiation/
  // So basically we're creating a new anonymous class that extends BuildImageResultCallback
  // BuildImageResultCallback is the parent of this new anonymous class
  private[DevBook] val buildImageCallback = new BuildImageResultCallback() {
    override def onNext(item: BuildResponseItem) = {
      val payload = item.getStream()

      // This is basically how I got around the fact
      // that the API call was giving me back the wrong image ID
      // I'm scanning the contents for the imageId and what happens is
      // createImage() is sleeping and waiting for a notification
      // that the imageId has been found
      //
      // https://javarevisited.blogspot.com/2015/07/how-to-use-wait-notify-and-notifyall-in.html
      if (payload.contains("Successfully built")) {
        imageIdHolder.synchronized {
          imageIdHolder ++= payload.substring(19)
          imageIdHolder.notifyAll()
        }
      }

      synchronizedPrintln(s"BuildImageResultCallback: ${payload}")
      
      // So because we've extended the class, this method (onNext) exists
      // in the parent
      // "super" here basically refers to the parent class
      // just like "this" refers to the current instance
      // So we're calling BuildImageResultCallback.onNext()
      super.onNext(item)
    }
  }

  // We're using a Java API and Java unlike Scala is not functional,
  // so the way it works is when you want a callback, you define an interface or abstract class
  // and the consumer of the API overrides the defined interface/class and then the function defined
  // in the interface or abstract class
  private[DevBook] class MyLogContainerResultCallback(snippetId: String) extends LogContainerResultCallback {
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
      if (snippetIdToContainerId.contains(snippetId)) {
        val payload = new String(frame.getPayload())
        if (!payload.contains("/usr/src/app") && !payload.contains("node") && !payload.contains("defaultName") && !payload.trim().isEmpty()) {
          // I'm synchronizing on the log because the
          // Java Virtual Machine has a happens-before relationship
          // that guarantees that if I synchronize on something and
          // it was synchronized before and written on, I will see
          // that write in memory; I was taught to think of it
          // as a cache flush where all CPUs flush their caches
          log.synchronized {
            log ++= payload 
            log ++= "\n"
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
    }

    override def toString(): String = {
      log.synchronized {
        log.toString()
      }
    }
  }

  // This callback runs after the container finishes I believe
  private[DevBook] val waitContainerResultCallback = new WaitContainerResultCallback() {
    override def onNext(waitResponse: WaitResponse) = {
      synchronizedPrintln("Wait response: " + waitResponse.toString())
    }
  }

  // This actually processes a QueryDocumentSnapshot
  // So when I get a query snapshot of size 2 that means
  // 2 snippets have been set to running so this callback handles
  // 1 snippet and it's called by eventListenerCallback for each one 
  val querySnapshotCallback =
    (doc: QueryDocumentSnapshot) => {
      val snippetId = doc.getId()
      synchronizedPrintln(s"snippetId: ${snippetId}")
      // Assuming a snippet has some text
      // Check if we've seen this snippet before
      // If so, remove the container
      // Either way, create the image first and then run container
      synchronizedPrintln(s"Option(doc.get(text)): ${Option(doc.get("text"))}")
      // As I said Option is a 1-element collection
      // An Option can have SOME value or have NONE
      // By calling foreach on an Option, it will only do something
      // if there's SOME value
      // https://alvinalexander.com/scala/best-practice-option-some-none-pattern-scala-idioms
      //
      // Option is just a really nice way of handling Java's null
      // Think of it like a box which may or may not contain a value
      //
      // So let's take an example
      // Option(null).foreach(value => println(value)) will not do anything but
      // Option(5).foreach(value => println(value)) will print out 5
      Option(doc.get("text")).foreach(indexJSContents => {
        synchronizedPrintln(s"Got text: ${indexJSContents}")
        // So concurrentMap.get() returns an Option so I have to do something to get the value
        // Option.getOrElse() is one of the things I can do to get the value
        //
        // So here I'm getting an Option[String] from looking up snippetId in the hashtable
        val containerIdOpt: Option[String] = snippetIdToContainerId.get(snippetId)
        // Now I'm checking if the Option (the box) has a value/isn't empty
        if (!containerIdOpt.isEmpty) {
          // So then I just get the actual value from the Option (the box)
          val containerId: String = containerIdOpt.getOrElse(null)

          // So Scala has something nice called Try/Success/Failure
          // It's a way to deal with and wrap around Java's exceptions
          // and use pattern matching for exception handling
          //
          // https://danielwestheide.com/blog/2012/12/26/the-neophytes-guide-to-scala-part-6-error-handling-with-try.html
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
          // If there is some FirestoreException
          // (i.e. e is not null)
          // Then we have an error and something went wrong
          case Some(e) =>
            System.err.synchronized {
              System.err.println(s"Listen failed: $e")
            }
          // Otherwise we received the query snapshot
          // And then we proceed to process the query snapshot
          // You can think of apply() as the same in Javascript
          // So as stated we are using a Java API
          //
          // Java is not functional so objects are always passed around
          // so I'm creating a new instance of a class that implements
          // the Consumer interface (an interface is just a contract
          // that says I will implement these behaviors/methods)
          //
          // https://docs.oracle.com/javase/8/docs/api/java/util/function/Consumer.html
          //
          // So I'm using foreach because QuerySnapshot implements the Iterable interface
          // which means I can iterate over it because a QuerySnapshot can contain
          // many QueryDocumentSnapshots
          // https://googlecloudplatform.github.io/google-cloud-java/google-cloud-clients/apidocs/com/google/cloud/firestore/QuerySnapshot.html
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
}
