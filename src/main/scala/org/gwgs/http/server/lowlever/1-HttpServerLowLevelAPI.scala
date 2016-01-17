package org.gwgs.http.server.lowlever

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import HttpMethods._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Sink, Flow}

import akka.stream.stage.{ Context, PushStage }
import scala.concurrent.Future

object HttpServerLowLevelAPI {

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
  
  
  /*
   * Following failures can be described more or less infrastructure related, they are failing
   * bindings or connections. Most of the time you won't need to dive into those very deeply,
   * as Akka will simply log errors of this kind anyway, which is a reasonable default for such problems.
   */
  
  /*
   * First type of failure is binding failure
   */
  def handleFailedBinding(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val ec = system.dispatcher
    
    val requestHandler: HttpRequest => HttpResponse = {
      case _: HttpRequest =>
        HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
          "<html><body>Hello world!</body></html>"))
    }
    
    val (host, port) = ("localhost", 80)
    val serverSource = Http().bind(host, port)

    val bindingFuture: Future[Http.ServerBinding] = serverSource
      .to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)
        connection handleWithSyncHandler requestHandler
      }) .run()

    bindingFuture onFailure {
      case ex: Exception => println("Failed to bind to {}:{}!", host, port)
    }
  }
  
  /*
   * Second type of failure is Source[IncomingConnection, _] signals a failure, after the server
   * has successfully bound to a port and the source starts running and emiting new incoming
   * connections.
   */
  def handleFailureBeforeConnection(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val ec = system.dispatcher
    
    val requestHandler: HttpRequest => HttpResponse = {
      case _: HttpRequest =>
        HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
          "<html><body>Hello world!</body></html>"))
    }
    
    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)
    val failureMonitor: ActorRef = system.actorOf(MyExampleMonitoringActor.props)

    import Http._
    val reactToTopLevelFailures = Flow[IncomingConnection]
      .transform { () =>
        new PushStage[IncomingConnection, IncomingConnection] {
          override def onPush(elem: IncomingConnection, ctx: Context[IncomingConnection]) =
            ctx.push(elem)

          override def onUpstreamFailure(cause: Throwable, ctx: Context[IncomingConnection]) = {
            failureMonitor ! cause
            super.onUpstreamFailure(cause, ctx)
          }
        }
      }

    serverSource
      .via(reactToTopLevelFailures)
      .to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)
        connection handleWithSyncHandler requestHandler
      }).run()
  }
  
  /*
   * The third type of failure that can occur is when the connection has been properly established,
   * however afterwards is terminated abruptly – for example by the client aborting the underlying
   * TCP connection. 
   */
  def handleFailureAfterConnection(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val ec = system.dispatcher
 
    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)

    val reactToConnectionFailure = Flow[HttpRequest]
      .transform { () =>
        new PushStage[HttpRequest, HttpRequest] {
          override def onPush(elem: HttpRequest, ctx: Context[HttpRequest]) =
            ctx.push(elem)

          override def onUpstreamFailure(cause: Throwable, ctx: Context[HttpRequest]) = {
            // handle the failure somehow
            super.onUpstreamFailure(cause, ctx)
          }
        }
      }

    val httpEcho = Flow[HttpRequest]
      .via(reactToConnectionFailure)
      .map { request =>
        // simple text "echo" response:
        HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, request.entity.dataBytes))
      }

    serverSource
      .runForeach { con =>
        con.handleWith(httpEcho)
      }
    
  }
  
}

//////////////////////////////////////////////////////////////////////////////////////////////
object MyExampleMonitoringActor {
  def props = Props[MyExampleMonitoringActor]
}

class MyExampleMonitoringActor extends Actor {
  def receive = {
    case t: Throwable => println(t.getMessage)
  }
}
