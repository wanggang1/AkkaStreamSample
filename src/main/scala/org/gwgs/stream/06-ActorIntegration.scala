package org.gwgs.stream

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.util.Timeout

import scala.concurrent.duration._

object ActorIntegration {

  /**
    * Stream to an actor using ask in mapAsync. The back-pressure of the stream is
    * maintained by the Future of the ask and the mailbox of the actor will not be
    * filled with more messages than the given parallelism of the mapAsync stage.
    *
    * The actor must reply to the sender() for each message from the stream. That
    * reply will complete the Future of the ask and it will be the element that
    * is emitted downstream from mapAsync.
    */
  def processWithActor(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import akka.pattern.ask
    implicit val askTimeout = Timeout(5 seconds)

    val externalActor: ActorRef = system.actorOf(Props[Translator])
    val words: Source[String, NotUsed] =
      Source(List("hello", "hi"))

    words
      .mapAsync(parallelism = 5)(elem => (externalActor ? elem).mapTo[String])
      // continue processing of the replies from the actor
      .map(_.toLowerCase)
      .runWith(Sink.foreach(println))
  }

  type MessageForActor = String

  /*
   * Source.queue can be used for emitting elements to a stream from an actor
   * (or from anything running outside the stream).
   */
  val queue: Source[MessageForActor, SourceQueueWithComplete[MessageForActor]] =
    Source.queue(10, OverflowStrategy.backpressure)

  /*
   * Creates a Source that is materialized as an [[akka.actor.ActorRef]].  Messages
   * sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   */
  val source: Source[MessageForActor, ActorRef] = Source.actorRef(10, OverflowStrategy.dropHead)
  
  /*
   * Sends the elements of the stream to the given ActorRef.  Sink.actorRef
   * will loss back-pressure from the actor, use Sink.actorRefWithAck or
   * mapAsync+ask instead if back-pressure needed
   */
  val ref: ActorRef = null
  val sink: Sink[MessageForActor, NotUsed] = Sink.actorRef(ref, "Done for onComplete")

}

////////////////////////// Actor Processor /////////////////////////////////////
class Translator extends Actor {
  def receive = {
    case word: String =>
      // ... process message
      val reply = s"TRANSLATED: ${word.toUpperCase}"
      sender ! reply // reply to the ask
  }
}
