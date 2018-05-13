package DevBook 

import DevBook.Utils._

import java.io.File

import scala.collection.mutable.{ListBuffer, StringBuilder}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Await}

object DockerMain {
  val lock = new AnyRef
  var flag: Boolean = false

  def main(args: Array[String]): Unit = {
    Future {
      val id = "oogabooga";

      val imageId = createImage(id)
      createAndRunContainer(imageId)
    } onComplete {
      case _ => println("Image creation done!")
    }

    lock.synchronized {
      try {
        while (flag == false) lock.wait()
      } catch {
        case e: InterruptedException => e.printStackTrace()
      }
    }
  }
}
