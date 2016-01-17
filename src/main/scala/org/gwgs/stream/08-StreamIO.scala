package org.gwgs

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, FlowShape , ActorAttributes}
import akka.stream.scaladsl._

import akka.util.ByteString

import java.io.File
import scala.concurrent.Await
import scala.concurrent.Future

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
    import akka.stream.io.Framing
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
    import akka.stream.io.Framing
    import akka.stream.stage.{ Context, PushStage, SyncDirective }

    val connection = Tcp().outgoingConnection("127.0.0.1", 8888)
 
    val replParser = new PushStage[String, ByteString] {
      override def onPush(elem: String, ctx: Context[ByteString]): SyncDirective = {
        elem match {
          case "q" ⇒ ctx.pushAndFinish(ByteString("BYE\n"))
          case _   ⇒ ctx.push(ByteString(s"$elem\n"))
        }
      }
    }

    val repl = Flow[ByteString]
      .via(Framing.delimiter(
        ByteString("\n"),
        maximumFrameLength = 256,
        allowTruncation = true))
      .map(_.utf8String)
      .map(text => println("Server: " + text))
      .map(_ => readLine("> "))
      .transform(() ⇒ replParser)

    connection.join(repl).run()
  }
  
  /*
   * Client interacts with server using Akka Streams over TCP
   */
  def replClient2(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.io.Framing
    import akka.stream.stage.{ Context, PushStage, SyncDirective }

    val connections = Tcp().bind("127.0.0.1", 8888)
 
    connections runForeach { connection =>
      val serverLogic = Flow.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        // server logic, parses incoming commands
        val commandParser = new PushStage[String, String] {
          override def onPush(elem: String, ctx: Context[String]): SyncDirective = {
            elem match {
              case "BYE" ⇒ ctx.finish()
              case _     ⇒ ctx.push(elem + "!")
            }
          }
        }

        import connection._
        val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!\n"

        val welcome = Source.single(ByteString(welcomeMsg))
        val echo = b.add(Flow[ByteString]
          .via(Framing.delimiter(
            ByteString("\n"),
            maximumFrameLength = 256,
            allowTruncation = true))
          .map(_.utf8String)
          .transform(() ⇒ commandParser)
          .map(_ + "\n")
          .map(ByteString(_)))

        val concat = b.add(Concat[ByteString]())
        // first we emit the welcome message,
        welcome ~> concat.in(0)
        // then we continue using the echo-logic Flow
        echo.outlet ~> concat.in(1)

        FlowShape(echo.in, concat.out)
      })

      connection.handleWith(serverLogic)
    }
  }
  
  /*
   * Since the current version of Akka (2.3.x) needs to support JDK6, the currently
   * provided File IO implementations are not able to utilise Asynchronous File IO
   * operations, as these were introduced in JDK7 (and newer). Once Akka is free to
   * require JDK8 (from 2.4.x) these implementations will be updated to make use of
   * the new NIO APIs (i.e. AsynchronousFileChannel).
   */
  def fileIO(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.stream.io._
    val file = new File("external/sample.csv")

    val fileLength: Future[Long] = FileIO.fromFile(file)
      .withAttributes(ActorAttributes.dispatcher("stream.my-blocking-dispatcher")) //to configure globally, changing the akka.stream.blocking-io-dispatcher
      .to(Sink.ignore)
      .run()
      
    val result = Await.result(fileLength, 1 second)
    println(s"File length is $result")
    
    FileIO.fromFile(file)
      .withAttributes(ActorAttributes.dispatcher("stream.my-blocking-dispatcher")) //to configure globally, changing the akka.stream.blocking-io-dispatcher
      .runForeach(file => println(s"$file"))
  }
  
}
