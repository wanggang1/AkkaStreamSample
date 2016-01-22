package org.gwgs.http.server.highlevel

import akka.http.scaladsl.server.{RequestContext, RouteResult}

import scala.concurrent.Future

/**
  * Created by gang.wang on 1/21/16.
  */
object Routes {

  // Definition of Route
  type Route = RequestContext ⇒ Future[RouteResult]

}
