package org.gwgs

import akka.actor.{ ActorRef, Props , ActorSystem, Actor }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.routing.{ ActorRefRoutee, RoundRobinRoutingLogic, Router }
import akka.stream.actor.{ ActorPublisher, ActorSubscriber , ActorSubscriberMessage, MaxInFlightRequestStrategy }
import akka.stream.scaladsl._

import scala.annotation.tailrec

object ActorIntegration {

  type MessageForActor = String //???
  
  val actorRef: ActorRef = null
  
  /*
   * Creates a Source that is materialized as an [[akka.actor.ActorRef]].  Messages
   * sent to this actor will be emitted to the stream if there is demand from downstream,
   * otherwise they will be buffered until request for demand is received.
   */
  val source: Source[MessageForActor, ActorRef] = Source.actorRef(10, OverflowStrategy.dropHead)
  
  /*
   * Sends the elements of the stream to the given ActorRef
   */
  val sink: Sink[MessageForActor, Unit] = Sink.actorRef(actorRef, "Done for onComplete")
  
  /*
   * Unlike Source.actorRef, this source created by Source.actorPublisher is stream
   * publisher with full control of stream back pressure
   */
  def actorPublish(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val jobManagerSource: Source[JobManager.Job, ActorRef] =
      Source.actorPublisher[JobManager.Job](JobManager.props)
    
    //Flow.runWith(jobManagerSource) will return the materialized value of jobManagerSource
    val ref: ActorRef = Flow[JobManager.Job]
      .map(_.payload.toUpperCase)
      .map { elem => println(elem); elem }
      .to(Sink.ignore)
      .runWith(jobManagerSource)

    // using the materialize the value, which is an ActorRef
    ref ! JobManager.Job("a")
    ref ! JobManager.Job("b")
    ref ! JobManager.Job("c")
  }
  
  /*
   * Unlike Sink.actorRef, this sink created by Sink.actorSubscriber is stream
   * subscriber with full control of stream back pressure
   */
  def actorSubscriber(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val replyTo = system.actorOf(Show.props)

    Source(1 to 117).map(WorkerPool.Msg(_, replyTo))
      .runWith(Sink.actorSubscriber(WorkerPool.props))
  }

}

//////////////////////////// For ActorPublisher ////////////////////////////////
object JobManager {
  def props: Props = Props[JobManager]
 
  final case class Job(payload: String)
  case object JobAccepted
  case object JobDenied
}
 
class JobManager extends ActorPublisher[JobManager.Job] {
  import akka.stream.actor.ActorPublisherMessage._
  import JobManager._
 
  val MaxBufferSize = 100
  var buf = Vector.empty[Job]
 
  def receive = {
    case job: Job if buf.size == MaxBufferSize =>
      sender() ! JobDenied
    case job: Job =>
      sender() ! JobAccepted
      if (buf.isEmpty && totalDemand > 0)
        onNext(job)
      else {
        buf :+= job
        deliverBuf()
      }
    case Request(_) =>
      deliverBuf()
    case Cancel =>
      context.stop(self)
  }
 
  @tailrec final def deliverBuf(): Unit =
    if (totalDemand > 0) {
      /*
       * totalDemand is a Long and could be larger than
       * what buf.splitAt can accept
       */
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onNext
      } else {
        val (use, keep) = buf.splitAt(Int.MaxValue)
        buf = keep
        use foreach onNext
        deliverBuf()
      }
    }
  
}

//////////////////// For ActorSubscriber ///////////////////////////////////////
object WorkerPool {
  case class Msg(id: Int, replyTo: ActorRef)
  case class Work(id: Int)
  case class Reply(id: Int)
  case class Done(id: Int)
 
  def props: Props = Props(new WorkerPool)
}
 
class WorkerPool extends ActorSubscriber {
  import WorkerPool._
  import ActorSubscriberMessage._
 
  val MaxQueueSize = 10
  var queue = Map.empty[Int, ActorRef]
 
  val router = {
    val routees = Vector.fill(3) {
      ActorRefRoutee(context.actorOf(Props[Worker]))
    }
    Router(RoundRobinRoutingLogic(), routees)
  }
 
  /*
   * Subclass must define the RequestStrategy to control stream back pressure.  After each incoming
   * message the ActorSubscriber will automatically invoke the RequestStrategy.requestDemand and
   * propagate the returned demand to the stream.
   *   - The provided WatermarkRequestStrategy is a good strategy if the actor performs work itself.
   *   - The provided MaxInFlightRequestStrategy is useful if messages are queued internally or delegated to other actors.
   *   - Custom RequestStrategies
   */
  override val requestStrategy = new MaxInFlightRequestStrategy(max = MaxQueueSize) {
    override def inFlightInternally: Int = queue.size
  }
 
  def receive = {
    case OnNext(Msg(id, replyTo)) =>
      queue += (id -> replyTo)
      assert(queue.size <= MaxQueueSize, s"queued too many: ${queue.size}")
      router.route(Work(id), self)
    case Reply(id) =>
      queue(id) ! Done(id)
      queue -= id
  }
}
 
class Worker extends Actor {
  import WorkerPool._
  def receive = {
    case Work(id) =>
      // ...
      sender() ! Reply(id)
  }
}

object Show {
  def props: Props = Props[Show]
}

class Show extends Actor {
  import WorkerPool.Done
  def receive = {
    case Done(id) => println(s"$id is done")
  }
}