package org.gwgs.http.server.highlever

import akka.actor.{ Actor, ActorSystem, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import HttpMethods._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink

import scala.language.postfixOps
import scala.concurrent.duration._


import scala.concurrent.Future

object Overview {

  def simpleMain(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val ec = system.dispatcher
    
    val route =
      path("hello") {
        get {
          complete {
            "Say hello to akka-http"
          }
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    Console.readLine() // for the future transformations
    
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
  }
  
  /*
   * "binding future" fails immediatly, App can react to it by listening on the Future's completion
   */
  def handleBindFailure(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val ec = system.dispatcher
 
    val handler = get {
      complete("Hello world!")
    }

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val bindingFuture: Future[Http.ServerBinding] =
      Http().bindAndHandle(handler, host, port)

    bindingFuture onFailure {
      case ex: Exception => println(s"Failed to bind to $host:$port!")
    }
  }
  
  /*
   * The body parts(Multipart.FormData.parts) are Source rather than all available right away,
   * and so is the individual body part(BodyPart) payload so application needs to consume those
   * streams both for the file and for the form fields.
   */
  def fileUploadSaved(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    import akka.http.scaladsl.model.Multipart.FormData.BodyPart
    import akka.stream.scaladsl.FileIO
    import java.io.File
    
    implicit val ec = system.dispatcher
    
    val uploadVideo =
      path("video") {
        entity(as[Multipart.FormData]) { formData =>

          // collect all parts of the multipart as it arrives into a map
          val allPartsF: Future[Map[String, Any]] = formData.parts.mapAsync[(String, Any)](1) {

            case b: BodyPart if b.name == "file" =>
              // stream into a file as the chunks of it arrives and return a future
              // file to where it got stored
              val file = File.createTempFile("upload", "tmp")
              b.entity.dataBytes.runWith(FileIO.toFile(file)).map(_ =>
                (b.name -> file))

            case b: BodyPart =>
              // collect form field values
              b.toStrict(2.seconds).map(strict =>
                (b.name -> strict.entity.data.utf8String))

          }.runFold(Map.empty[String, Any])((map, tuple) => map + tuple)

          val done = allPartsF.map { allParts =>
            // You would have some better validation/unmarshalling here
            /*
            db.create(Video(
              file = allParts("file").asInstanceOf[File],
              title = allParts("title").asInstanceOf[String],
              author = allParts("author").asInstanceOf[String]))
            */
          }

          // when processing have finished create a response for the user
          onSuccess(allPartsF) { allParts =>
            complete {
              "ok!"
            }
          }
        }
      }
  }
  
  /*
   * The body parts(Multipart.FormData.parts) are Source, so is the individual
   * body part(BodyPart) payload.
   */
  def fileUploadProcessed(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    import akka.http.scaladsl.model.Multipart.FormData.BodyPart
    import akka.stream.io.Framing
    import akka.util.ByteString
    
    val splitLines = Framing.delimiter(ByteString("\n"), 256)
    val metadataActor = system.actorOf(MyExampleMetadataActor.props)
 
    val csvUploads =
      path("metadata" / LongNumber) { id =>
        entity(as[Multipart.FormData]) { formData =>
          val done = formData.parts.mapAsync(1) {
            case b: BodyPart if b.filename.exists(_.endsWith(".csv")) =>
              b.entity.dataBytes
                .via(splitLines)
                .map(_.utf8String.split(",").toVector)
                .runForeach { csv => 
                  metadataActor ! MyExampleMetadataActor.Entry(id, csv) 
                }
            case _ => Future.successful(Unit)
          }.runWith(Sink.ignore)

          // when processing have finished create a response for the user
          onSuccess(done) {
            complete {
              "ok!"
            }
          }
        }
      }
  }
  
}

//////////////////////////////////////////////////////////////////////////////////////////////
object MyExampleMetadataActor {
  case class Entry(id: Long, cvs: Vector[String])
  
  def props = Props[MyExampleMetadataActor]
}

class MyExampleMetadataActor extends Actor {
  def receive = {
    case _ => println("Received!")
  }
}
