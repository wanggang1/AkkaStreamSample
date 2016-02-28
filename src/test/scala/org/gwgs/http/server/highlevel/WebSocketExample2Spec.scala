package org.gwgs.http.server.highlevel

import akka.http.scaladsl.model.ws.{ BinaryMessage, TextMessage, Message }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.{WSProbe, ScalatestRouteTest}
import akka.stream.scaladsl.{Sink, Source, Flow}
import akka.util.ByteString

import org.scalatest.{Matchers, WordSpec}

import scala.language.postfixOps
import scala.concurrent.duration._

class WebSocketExample2Spec extends WordSpec with Matchers with ScalatestRouteTest {

  "The WebSocket service" should {

    "handle websocket requests" in {
      def greeter: Flow[Message, Message, Any] =
        Flow[Message].mapConcat {
          case tm: TextMessage ⇒
            TextMessage(Source.single("Hello ") ++ tm.textStream ++ Source.single("!")) :: Nil
          case bm: BinaryMessage ⇒
            // ignore binary messages but drain content to avoid the stream being clogged
            bm.dataStream.runWith(Sink.ignore)
            Nil
        }

      val websocketRoute =
        path("greeter") {
          handleWebSocketMessages(greeter)
        }

      // create a testing probe representing the client-side
      val wsClient = WSProbe()

      // WS creates a Websocket request for testing
      WS("/greeter", wsClient.flow) ~> websocketRoute ~> check {
          // check response for WS Upgrade headers
          isWebSocketUpgrade shouldEqual true

          // manually run a WS conversation
          wsClient.sendMessage("Peter")
          wsClient.expectMessage("Hello Peter!")

          wsClient.sendMessage(BinaryMessage(ByteString("abcdef")))
          wsClient.expectNoMessage(100 millis)

          wsClient.sendMessage("John")
          wsClient.expectMessage("Hello John!")

          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }
    }

  }
}