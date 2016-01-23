package org.gwgs.http.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import scala.concurrent.Future

/**
  * For full-control over when HTTP connections are opened/closed and how requests are scheduled across them.
  */
object ConnectionLevelAPI {

  def openHttpConnection(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    /*
     * no connection is attempted until connectionFlow is actually materialized! If this flow is materialized
     * several times then several independent connections will be opened (one per materialization).
     */
    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection("akka.io")

    /*
     * materialize connectionFlow, and get a materialized response.
     */
    val responseFuture: Future[HttpResponse] =
      Source.single(HttpRequest(uri = "/"))
        .via(connectionFlow)
        .runWith(Sink.head)
  }

}
