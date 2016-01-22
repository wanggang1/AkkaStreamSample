package org.gwgs.http.server.highlevel

import akka.http.scaladsl.server.Directives._

/**
  * Generally, when routes work with more than 3 extractions it's a good idea to introduce a case class for these
  * and resort to case class extraction. Especially since it supports another nice feature: validation.
  */
object CaseClassExtraction {

  case class Color(red: Int, green: Int, blue: Int)

  val route =
    path("color") {
      parameters('red.as[Int], 'green.as[Int], 'blue.as[Int]).as(Color) { color =>
        complete(s"Got color $color")
      }
    }


  /*
   * Akka HTTP's case class extraction logic can properly pick up all error messages and generate a ValidationRejection
   * if something goes wrong. By default, ValidationRejections are converted into 400 Bad Request error response by the
   * default RejectionHandler, if no subsequent route successfully handles the request.
   */
  case class ColorWithName(name: String, red: Int, green: Int, blue: Int) {
    require(!name.isEmpty, "color name must not be empty")
    require(0 <= red && red <= 255, "red color component must be between 0 and 255")
    require(0 <= green && green <= 255, "green color component must be between 0 and 255")
    require(0 <= blue && blue <= 255, "blue color component must be between 0 and 255")
  }

  val route1 =
    (path("color" / Segment) & parameters('r.as[Int], 'g.as[Int], 'b.as[Int])).as(ColorWithName) { color =>
      complete(s"Got color $color")
    }

}
