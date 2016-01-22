package org.gwgs.http.common

object JsonSupport {

  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
  import akka.http.scaladsl.server.Directives
  
  import spray.json._
  
  // domain model
  final case class Item(name: String, id: Long)
  final case class Order(items: List[Item])

  //define json format instances in a support trait:
  trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
    implicit val itemFormat = jsonFormat2(Item)
    implicit val orderFormat = jsonFormat1(Order) // contains List[Item]
  }

  // use it where json (un)marshalling is needed
  class MyJsonService extends Directives with JsonSupport {

    // format: OFF
    val route =
      get {
        pathSingleSlash {
          complete {
            Item("thing", 42) // will render as JSON
          }
        }
      } ~
      post {
        entity(as[Order]) { order => // will unmarshal JSON to Order
          val itemsCount = order.items.size
          val itemNames = order.items.map(_.name).mkString(", ")
          complete(s"Ordered $itemsCount items: $itemNames")
        }
      }
    // format: ON
    
  }

}
