package org.gwgs.http.server.highlevel

import akka.http.scaladsl.server.{Directive, Directive1}
import akka.http.scaladsl.server.Directives._

/**
  * Created by gang.wang on 1/21/16.
  */
object CustomDirectives {
  //////////////////////////////
  // Configuration Labeling
  //////////////////////////////
  val getOrPut = get | put

  val route = getOrPut { complete("ok") }

  // tests:
  /*
  Get("/") ~> route ~> check {
    responseAs[String] shouldEqual "ok"
  }

  Put("/") ~> route ~> check {
    responseAs[String] shouldEqual "ok"
  }
  */

  //////////////////////////////////////////////////////////////
  // map - extract from single-value Directive and transform (can only complete)
  // tmap - extract from multiple-value Directive and transform
  //////////////////////////////////////////////////////////////
  val textParam: Directive1[String] =
    parameter("text".as[String])

  val lengthDirective: Directive1[Int] =
    textParam.map(text => text.length)

  // tests:
  /*
  Get("/?text=abcdefg") ~> lengthDirective(x => complete(x.toString)) ~> check {
    responseAs[String] === "7"
  }
  */

  val twoIntParameters: Directive[(Int, Int)] =
    parameters(("a".as[Int], "b".as[Int]))

  val myDirective: Directive1[String] =
    twoIntParameters.tmap {
      case (a, b) => (a + b).toString
    }

  // tests:
  /*
  Get("/?a=2&b=5") ~> myDirective(x => complete(x)) ~> check {
    responseAs[String] === "7"
  }
  */

  ///////////////////////////////////////////////////////////////////////////////////
  // flatMap - extract from single-value Directive and transform (can complete or reject)
  // tflatMap - extract from multiple-value Directive and transform
  ///////////////////////////////////////////////////////////////////////////////////
  val intParameter: Directive1[Int] = parameter("a".as[Int])

  val myDirective2: Directive1[Int] =
    intParameter.flatMap {
      case a if a > 0 => provide(2 * a) //Directive1
      case _          => reject  //StandardRoute
    }

  // tests:
  /*
  Get("/?a=21") ~> myDirective2(i => complete(i.toString)) ~> check {
    responseAs[String] === "42"
  }
  Get("/?a=-18") ~> myDirective2(i => complete(i.toString)) ~> check {
    handled === false
  }
  */




}
