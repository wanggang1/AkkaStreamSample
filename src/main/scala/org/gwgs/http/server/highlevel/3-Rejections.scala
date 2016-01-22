package org.gwgs.http.server.highlevel

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import StatusCodes._
import Directives._


/**
  * Created by gang.wang on 1/22/16.
  */
object Rejections {

  import akka.http.scaladsl.coding.Gzip

  /*
   * For uncompressed POST requests this route structure would initially yield two rejections:
   *    1. a MethodRejection produced by the get directive, which rejected because the request is not a GET request.
   *    2. an UnsupportedRequestEncodingRejection produced by the decodeRequestWith directive (which only accepts
   *      gzip-compressed requests here).
   * In reality, a TransformationRejection produced by the post directive. It "cancels" the MethodRejection produced
   * by get directive.  Eventually RejectionHandler only sees UnsupportedRequestEncodingRejection.
   */
  val route =
    path("order") {
      get {
        complete("Received GET")
      } ~
      post {
        decodeRequestWith(Gzip) {
          complete("Received compressed POST")
        }
      }
    }


  def customRejectionHandling(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    implicit def myRejectionHandler =
      RejectionHandler.newBuilder()
        .handle { case MissingCookieRejection(cookieName) =>
          complete(HttpResponse(BadRequest, entity = "No cookies, no service!!!"))
        }
        .handle { case AuthorizationFailedRejection ⇒
          complete((Forbidden, "You're out of your depth!"))
        }
        .handle { case ValidationRejection(msg, _) ⇒
          complete((InternalServerError, "That wasn't valid! " + msg))
        }
        .handleAll[MethodRejection] { methodRejections ⇒
          val names = methodRejections.map(_.supported.name)
          complete((MethodNotAllowed, s"Can't do that! Supported: ${names mkString " or "}!"))
        }
        .handleNotFound {
          complete((NotFound, "Not here!"))
        }
        .result()

    Http().bindAndHandle(route, "localhost", 8080)
  }

}
