package org.gwgs.http.client

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, HttpResponse, HttpRequest}
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.{Sink, Source, Flow}
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.Try

/**
  * For letting Akka HTTP perform all connection management
  *
  * The request-level API is the most convenient way of using Akka HTTP's client-side functionality. The request-level
  * API is implemented on top of a connection pool that is shared inside the ActorSystem.  DO NOT BLOCK with this API,
  * using Connection-Level Client-Side API for long-running requests.
  */
object RequestLevelAPI {

  /*
   * ?????, need to find a good example
   */
  def flowBased(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    val connectionFlow = Http().superPool[Int]()

    /*
     * materialize connectionFlow, and get a materialized response.
     */
    val responseFuture: Future[(Try[HttpResponse], Int)] =
      Source.single(HttpRequest(uri = "http://akka.io/") -> 42)
        .via(connectionFlow)
        .runWith(Sink.head)

  }

  /*
   * For simply needing the HTTP response for a certain request and don't want to bother with setting up
   * a full-blown streaming infrastructure.
   */
  def futureBased(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    /*
     * Just like in the case of the super-pool flow described above, the request must have either an absolute URI or
     * a valid Host header, otherwise the returned future will be completed with an error.
     */
    val fResponse: Future[HttpResponse] = Http(system).singleRequest(HttpRequest(uri = "http://akka.io"))

  }

}


/**
  * Using the Future-Based API in Actors
  */
class HttpConsumerActor extends Actor
  with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))
  
  val http = Http(context.system)

  override def preStart() = {
    http.singleRequest(HttpRequest(uri = "http://akka.io"))
      .pipeTo(self)
  }

  def receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      log.info("Got response, body: " + entity.dataBytes.runFold(ByteString(""))(_ ++ _))
    case HttpResponse(code, _, _, _) =>
      log.info("Request failed, response code: " + code)
  }

}
