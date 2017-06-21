package org.gwgs.stream

import akka.actor.ActorSystem
import akka.stream.{ActorAttributes, ActorMaterializer, FlowShape, IOResult}
import akka.stream.scaladsl._
import akka.util.ByteString
import java.nio.file.Paths

import akka.NotUsed

import scala.concurrent.Await
import scala.concurrent.Future
import scala.io.StdIn.readLine
import scala.language.postfixOps
import scala.concurrent.duration._

object StreamIO {

  /*
   * Stream over TCP
   * 
   * the connection can only be materialized once, since it directly corresponds
   * to an existing and already accepted TCP connection
   * 
   * To run this:
   * $ echo -n "Hello World" | netcat 127.0.0.1 8888
   */
  def tcpServer(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.scaladsl.Framing
    import Tcp.{ IncomingConnection, ServerBinding }
 
    val connections: Source[IncomingConnection, Future[ServerBinding]] =
      Tcp().bind("127.0.0.1", 8888)

    connections runForeach { connection =>
      println(s"New connection from: ${connection.remoteAddress}")

      val echo = Flow[ByteString]
        .via(Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
        .map(_.utf8String)
        .map(_ + "!!!\n")
        .map(ByteString(_))

      connection.handleWith(echo)
    }
  }

  /*
   * Client interacts with server using Akka Streams over TCP
   */
  def replClient(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.scaladsl.Framing

    val connection = Tcp().outgoingConnection("127.0.0.1", 8888)

    val replParser: Flow[String,ByteString,NotUsed] =
      Flow[String].takeWhile(_ != "q")
        .concat(Source.single("BYE"))
        .map(elem => ByteString(s"$elem\n"))

    val repl = Flow[ByteString]
      .via(Framing.delimiter(
        ByteString("\n"),
        maximumFrameLength = 256,
        allowTruncation = true))
      .map(_.utf8String)
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .via(replParser)

    connection.join(repl).run()
  }

  /*
   * Client interacts with server using Akka Streams over TCP
   */
  def replClient2(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.scaladsl.Framing

    val connections = Tcp().bind("127.0.0.1", 8888)

    connections runForeach { connection =>
      // server logic, parses incoming commands
      val commandParser = Flow[String].takeWhile(_ != "BYE").map(_ + "!")

      import connection._
      val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!"
      val welcome = Source.single(welcomeMsg)

      val serverLogic = Flow[ByteString]
        .via(Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
        .map(_.utf8String)
        .via(commandParser)
        // merge in the initial banner after parser
        .merge(welcome)
        .map(_ + "\n")
        .map(ByteString(_))

      connection.handleWith(serverLogic)
    }
  }

  /*
   * Akka Streams provide simple Sources and Sinks that can work with ByteString
   * instances to perform IO operations on files.
   */
  def fileIO(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.scaladsl._
    val pathToFile = Paths.get("external/sample.csv")

    val fileResult: Future[IOResult] = FileIO.fromPath(pathToFile)
      .withAttributes(ActorAttributes.dispatcher("stream.my-blocking-dispatcher")) //to configure globally, changing the akka.stream.blocking-io-dispatcher
      .to(Sink.ignore)
      .run()

    val result = Await.result(fileResult, 1 second)
    println(s"File result is $result")

    FileIO.fromPath(pathToFile)
      .withAttributes(ActorAttributes.dispatcher("stream.my-blocking-dispatcher"))
      .runForeach(file => println(s"$file"))
  }

}
