package org.gwgs.http.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}

import scala.concurrent.Future
import scala.util.Try

/**
  * for letting Akka HTTP manage a connection-pool to one specific host/port endpoint
  */
object HostLevelAPI {

  def connect(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    // construct a pool client flow with context type `Int`
    val poolClientFlow = Http().cachedHostConnectionPool[Int]("akka.io")

    val responseFuture: Future[(Try[HttpResponse], Int)] =
      Source.single(HttpRequest(uri = "/") -> 42)
        .via(poolClientFlow)
        .runWith(Sink.head)

  }

}
