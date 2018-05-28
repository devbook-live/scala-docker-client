package DevBook 

import DevBook.Utils._
import DevBook.FirebaseService.db

import java.io.File

import scala.collection.mutable.{ListBuffer, StringBuilder}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration

import scala.util.{Try, Success, Failure}

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, HttpEntity, ContentTypes}
import akka.http.scaladsl.server.{Directives, ExceptionHandler, RejectionHandler, Route}
import akka.stream.ActorMaterializer

import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import Directives._

// object declares a singleton class
object DockerMain {
  // Just an object
  val lock = new AnyRef
  val flag: Boolean = false

  def route: Route = {
    // Your CORS settings are loaded from `application.conf`

    // Your rejection handler
    val rejectionHandler = corsRejectionHandler withFallback RejectionHandler.default

    // Your exception handler
    val exceptionHandler = ExceptionHandler {
      case e: NoSuchElementException => complete(StatusCodes.NotFound -> e.getMessage)
    }

    // Combining the two handlers only for convenience
    val handleErrors = handleRejections(rejectionHandler) & handleExceptions(exceptionHandler)

    // Note how rejections and exceptions are handled *before* the CORS directive (in the inner route).
    // This is required to have the correct CORS headers in the response even when an error occurs.
    handleErrors {
      cors() {
        handleErrors {
          pathSingleSlash {
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
                 "Hello World!"))
          } ~
          path("ping") {
            complete("pong")
          } ~
          path("pong") {
            failWith(new NoSuchElementException("pong not found, try with ping"))
          }
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    // On shutdown
    scala.sys.addShutdownHook {
      synchronizedPrintln("Stopping HTTP server...")

      synchronizedPrintln("Removing all containers...")
      // Forcefully remove all the containers still in the hashtable
      // So the foreach of ConcurrentMap takes in an anonymous function
      // and that anonymous function takes in a 2-element tuple (think of it like a 2-element array)
      // So I'm deconstructing/pattern matching on that tuple to get snippetId and containerId
      snippetIdToContainerId.foreach({ case (snippetId, containerId) => removeContainer(snippetId, containerId) })
    }

    snippetsSubscribe()

    // set up ActorSystem and other dependencies here
    //#main-class
    //#server-bootstrapping
    implicit val system: ActorSystem = ActorSystem("ScalaDockerClientAkkaHttpServer")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    //#server-bootstrapping

    //#main-class
    // from the UserRoutes trait
    //#main-class

    lazy val port = Try(sys.env("PORT")) match {
      case Success(portNum) => portNum.toInt
      case Failure(_) => 8080
    }

    //#http-server
    Http().bindAndHandle(route, "0.0.0.0", port)

    println(s"Server online at http://0.0.0.0:${port}/")

    Await.result(system.whenTerminated, Duration.Inf)
    //#http-server
    //#main-class

    // Every object in Java has what's called an intrinsic lock or monitor lock
    // So you call synchronized on that lock
    // And what happens is that when one thread has acquired a lock
    // no one else can acquire that lock so the synchronized method blocks/waits
    //
    // What I'm doing here is an alternative to wait is called busy-waiting
    // Busy waiting is very CPU inefficient but essentially it's like
    // while (true) { }
    // That's busy waiting
    //
    // So the way this works is the Main thread acquires a lock
    // and then it waits (it releases hold of the lock) and waits
    // When some other thread calls notifyAll() on the same lock
    // it will wake up (and the flag would be set to true)
    // In this case though I'm trying to do something like an
    // infinite loop so I'm not having another thread call notifyAll()
    // to wake up the main thread
    // The try-catch is necessary as threads can be interrupted
    // (like when you press Ctrl-C)
    lock.synchronized {
      try {
        while (flag == false) lock.wait()
      } catch {
        case e: InterruptedException => e.printStackTrace()
      }
    }
  }
}
