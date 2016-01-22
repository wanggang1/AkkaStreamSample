package org.gwgs.http.server.highlevel

import akka.actor.ActorSystem
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import StatusCodes._
import Directives._
import akka.stream.ActorMaterializer


/**
  * Created by gang.wang on 1/22/16.
  */
object ExceptionHandling {

  def exceptionHandlingExplicit(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    val myExceptionHandler = ExceptionHandler {
      case _: ArithmeticException =>
        extractUri { uri =>
          println(s"Request to $uri could not be handled normally")
          complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
        }
    }

    val route =
      handleExceptions(myExceptionHandler) {
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
      }

    Http().bindAndHandle(route, "localhost", 8080)

  }

  def exceptionHandlingImplicit(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {

    implicit def myExceptionHandler = ExceptionHandler {
      case _: ArithmeticException =>
        extractUri { uri =>
          println(s"Request to $uri could not be handled normally")
          complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
        }
    }

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

    Http().bindAndHandle(route, "localhost", 8080)

  }

}
