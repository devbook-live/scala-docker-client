package DevBook 

import DevBook.Utils._
import DevBook.FirebaseService.db

import java.io.File

import scala.collection.mutable.{ListBuffer, StringBuilder}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.ServletException
 
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler
 
// object declares a singleton class
object DockerMain {
  // Just an object
  val lock = new AnyRef
  val flag: Boolean = false
  var serverOpt: Option[Server] = None


  def jettyServer(): Unit = {
    Future {
      val handler = new AbstractHandler() {
        override def handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
          response.setContentType("text/html;charset=utf-8")
          response.setStatus(HttpServletResponse.SC_OK)
          baseRequest.setHandled(true)
          response.getWriter().println("<h1>Hello World</h1>")
        }
      }

      val port = if(System.getenv("PORT") != null) System.getenv("PORT").toInt else 8080
      serverOpt = Some(new Server(port))

      serverOpt.foreach(server => {
        server.setHandler(handler)

        synchronizedPrintln("Starting HTTP server...")
        server.start()
        server.join()
      })
    }
  }

  def main(args: Array[String]): Unit = {
    // On shutdown
    scala.sys.addShutdownHook {
      synchronizedPrintln("Stopping HTTP server...")
      // Stop the HTTP server
      serverOpt.foreach(server => server.stop())

      synchronizedPrintln("Removing all containers...")
      // Forcefully remove all the containers still in the hashtable
      // So the foreach of ConcurrentMap takes in an anonymous function
      // and that anonymous function takes in a 2-element tuple (think of it like a 2-element array)
      // So I'm deconstructing/pattern matching on that tuple to get snippetId and containerId
      snippetIdToContainerId.foreach({ case (snippetId, containerId) => removeContainer(snippetId, containerId) })
    }

    jettyServer()
    snippetsSubscribe()

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
