package org.gwgs

import akka.actor.ActorSystem
import akka.stream.{ ActorAttributes, ActorMaterializer, ActorMaterializerSettings }
import akka.stream.Supervision
import akka.stream.scaladsl._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object ErrorHandling {

  /*
   * By default Stop strategy is used for all exceptions. The stream will be completed with failure.
   */
  def default(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val source = Source(0 to 5).map(100 / _)
    val sum = source.runWith(Sink.fold(0)(_ + _))
    
    Await.ready(sum, 1 second).value.get match {
      case Failure(e: ArithmeticException) => println("ArithmeticException")
      case Failure(e) => println("Other, $e")
      case _ => println("Success")
    }
  }
  
  /*
   * Resume the processing, the elements that cause the exception are effectively dropped.
   * 
   * Note: dropping elements may result in deadlocks in graphs with cycles.
   */
  def overrideWithResume(implicit system: ActorSystem) = {
    val decider: Supervision.Decider = {
      case _: ArithmeticException => Supervision.Resume
      case _                      => Supervision.Stop
    }
    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withSupervisionStrategy(decider))

    val source = Source(0 to 5).map(100 / _)
    val sum = source.runWith(Sink.fold(0)(_ + _))
    
    Await.ready(sum, 1 second).value.get match {
      case Failure(e: ArithmeticException) => println("ArithmeticException")
      case Failure(e) => println("Other, $e")
      case Success(i) => println(s"Success, $i")
    }
  }
  
  def overrideInFlow(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val decider: Supervision.Decider = {
      case _: ArithmeticException => Supervision.Resume
      case _                      => Supervision.Stop
    }
    val flow = Flow[Int]
      .filter(100 / _ < 50).map(elem => 100 / (5 - elem))
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
    val source = Source(0 to 5).via(flow)
    val sum = source.runWith(Sink.fold(0)(_ + _))
    
    Await.ready(sum, 1 second).value.get match {
      case Failure(e: ArithmeticException) => println("ArithmeticException")
      case Failure(e) => println("Other, $e")
      case Success(i) => println(s"Success, $i")
    }
  }
 
  /*
   * Restart works in a similar way as Resume with the addition that accumulated
   * state, if any, of the failing processing stage will be reset.
   */
  def overrideWithRestart(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val decider: Supervision.Decider = {
      case _: IllegalArgumentException => Supervision.Restart
      case _                           => Supervision.Stop
    }
    
    val flow = Flow[Int]
      .scan(0) { (acc, elem) =>
        if (elem < 0) throw new IllegalArgumentException("negative not allowed")
        else acc + elem
      }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))
      
    //the negative element cause the scan stage to be restarted from 0 again.
    val source = Source(List(1, 3, -1, 5, 7)).via(flow)
    val result = source.grouped(1000).runWith(Sink.head)
    
    Await.ready(result, 1 second).value.get match {
      case Failure(e: IllegalArgumentException) => println("IllegalArgumentException")
      case Failure(e) => println("Other, $e")
      case Success(i) => println(s"Success, $i")
    }
  }
  
  /*
   * Stream supervision can also be applied to the futures of mapAsync.  if not applied,
   * the default stopping strategy would complete the stream with failure on the first 
   * Future that was completed with Failure. 
   */
  def fromExternal(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import QuickStart.{ akkaTag, tweets }
    import ActorAttributes.supervisionStrategy
    import Supervision.resumingDecider  //always resume no matter what exception
    
    val authors: Source[Author, Unit] = tweets
      .filter(_.hashtags.contains(akkaTag))
      .map(_.author)
      
    val emailAddresses: Source[String, Unit] =
      authors.via(
        Flow[Author].mapAsync(4)(author => lookupEmail(author.handle))
          .withAttributes(supervisionStrategy(resumingDecider)))
      
    emailAddresses.runWith(Sink.foreach(println))
  }
  
  //////////////////////////////////////////////////////////////////////////////
  import scala.concurrent.ExecutionContext.Implicits.global
  
  def lookupEmail(handle: String): Future[String] = Future {
    val parts = handle.split(" ")
    
    //drop the first one
    if (parts.head == "1") throw new IllegalArgumentException("start with 1")
    else parts.last
  }
}
