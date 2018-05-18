package DevBook 

import DevBook.Utils._
import DevBook.FirebaseService.db

import java.io.File

import scala.collection.mutable.{ListBuffer, StringBuilder}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}

// object declares a singleton class
object DockerMain {
  // Just an object
  val lock = new AnyRef
  val flag: Boolean = false

  def main(args: Array[String]): Unit = {
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
