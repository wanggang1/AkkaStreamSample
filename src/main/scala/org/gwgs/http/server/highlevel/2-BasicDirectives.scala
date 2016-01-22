package org.gwgs.http.server.highlevel

import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.server.Route

/**
  * Created by gang.wang on 1/21/16.
  *
  * A directive is a container that provides a tuple of values to create an inner route.
  * type Directive0 = Directive[Unit]
  * type Directive1[T] = Directive[Tuple1[T]]
  *
  * Directive Structure:
  *
  * name(arguments) { extractions =>
  *   ... // inner route
  * }
  *
  */
object BasicDirectives {

  val route1: Route = { ctx => ctx.complete("yeah") }

  val route2: Route = { ctx =>
    if (ctx.request.method == HttpMethods.GET)
      ctx.complete("Received GET")
    else
      ctx.complete("Received something else")
  }

  /*
   * However, when using Akka HTTP's Routing DSL you should almost never have to fall back to
   * creating routes via Route function literals that directly manipulate the RequestContext.
   */
  import akka.http.scaladsl.server.Directives._

  val route11: Route = complete("yeah")

  val route21: Route =
    get {
      complete("Received GET")
    } ~
    complete("Received something else")


  val route3: Route =
    path("order" / IntNumber) { id =>
      get {
        complete {
          "Received GET request for order " + id
        }
      } ~
      put {
        complete {
          "Received PUT request for order " + id
        }
      }
    }

  val route31 =
    path("order" / IntNumber) { id =>
      (get | put) {
        extractMethod { m =>
          complete(s"Received ${m.name} request for order $id")
        }
      }
    }

  val getOrPut = get | put

  val route32 =
    path("order" / IntNumber) { id =>
      getOrPut {
        extractMethod { m =>
          complete(s"Received ${m.name} request for order $id")
        }
      }
    }

  val route33 =
    ( path("order" / IntNumber) & getOrPut & extractMethod ) { (id, m) =>
      complete(s"Received ${m.name} request for order $id")
    }

}
