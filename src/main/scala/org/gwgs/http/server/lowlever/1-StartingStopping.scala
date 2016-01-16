package org.gwgs.http.server.lowlever

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Sink }

import scala.concurrent.Future

object StartingStopping {

  def start(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().bind(interface = "localhost", port = 8080)

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection => // foreach materializes the source
        println("Accepted new connection from " + connection.remoteAddress)
        // ... and then actually handle the connection
      }).run()
  }
  
  def startWithHandler(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    import HttpMethods._
    
    val serverSource = Http().bind(interface = "localhost", port = 8080)
 
    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
          "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
        HttpResponse(entity = "PONG!")

      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
        sys.error("BOOM!")

      case _: HttpRequest =>
        HttpResponse(404, entity = "Unknown resource!")
    }

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)

        connection handleWithSyncHandler requestHandler
        // this is equivalent to
        // connection handleWith { Flow[HttpRequest] map requestHandler }
      }).run()
  }
  
}
