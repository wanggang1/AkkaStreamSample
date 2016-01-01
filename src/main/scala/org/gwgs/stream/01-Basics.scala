package org.gwgs

import akka.actor.{ ActorSystem, Cancellable }
import akka.stream.{ ActorMaterializer, ClosedShape }
import akka.stream.scaladsl._

import scala.concurrent.{ Await, ExecutionContext, Future , Promise}
import scala.concurrent.duration._

object Basics {
  
  def basic(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    println("///////////////// Basic /////////////////")
    
    val source = Source(1 to 10)
    val sink = Sink.fold[Int, Int](0)(_ + _)

    // connect the Source to the Sink, obtaining a RunnableGraph
    // toMat to indicate the plan to transform the materialized value of the source and sink
    // Keep.right - function to return the materialized value of sink
    val runnable: RunnableGraph[Future[Int]] = source.toMat(sink)(Keep.right)

    // materialize the flow and get the value of the FoldSink
    val sum: Future[Int] = runnable.run()
    val result = Await.result(sum, 1 second)
    println(s"result = $result")
       
    //Processing stages are immutable, connecting them returns
    // a new processing stage.  exising is not affected
    // Source.runWith materializes the flow and returns Sink's materialized value
    val zeroes = source.map(_ => 0)
    zeroes.runWith(sink) // 0
  
    // Convenient method, materialize the flow, getting the Sinks materialized value
    val sum1: Future[Int] = source.runWith(sink)
    val result1 = Await.result(sum1, 1 second)
    println(s"result1 = $result1")

  }
  
  /*
   * Different ways to wire up
   */
  def wireup(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    println("///////////////// Wireup /////////////////")
    
    //Do not not need materialized because sink is side-effect only and the return type is Unit???

    // Explicitly creating and wiring up a Source, Sink and Flow
    val pipeline1: RunnableGraph[Unit] =
      Source(1 to 6).via(Flow[Int].map(_ * 2)).to(Sink.foreach(i => println(s"Explicitly : $i")))
    pipeline1.run()
    
    // Starting from a Source
    val source1 = Source(1 to 6).map(_ * 2)
    val pipeline2: RunnableGraph[Unit] = source1.to(Sink.foreach(i => println(s"Start From Source : $i")))
//    pipeline2.run()
    
    // Starting from a Sink
    val sink1: Sink[Int, Unit] = Flow[Int].map(_ * 2).to(Sink.foreach(i => println(s"Start From Sink : $i")))
    val pipeline3: RunnableGraph[Unit] = Source(1 to 6).to(sink1)
//    pipeline3.run()
    
    // Broadcast to a sink inline
    val otherSink: Sink[Int, Unit] =
      Flow[Int].alsoTo(Sink.foreach(i => println(s"Broadcast : $i"))).to(Sink.ignore)
    val pipeline4 = Source(1 to 6).to(otherSink)
//    pipeline4.run
  }

  /*
   * Combining materialized values
   */
  def combine(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    println("///////////////// Combine /////////////////")
    
    // An source that can be signalled explicitly from the outside
    val source: Source[Int, Promise[Option[Int]]] = Source.maybe[Int]

    // A flow that internally throttles elements to 1/second, and returns a Cancellable
    // which can be used to shut down the stream
    val flow: Flow[Int, Int, Cancellable] = null //throttler

    // A sink that returns the first element of a stream in the returned Future
    val sink: Sink[Int, Future[Int]] = Sink.head[Int]

    // By default, the materialized value of the leftmost stage is preserved
    val r1: RunnableGraph[Promise[Option[Int]]] = source.via(flow).to(sink)

    // Simple selection of materialized values by using Keep.right
    val r2: RunnableGraph[Cancellable] = source.viaMat(flow)(Keep.right).to(sink)
    val r3: RunnableGraph[Future[Int]] = source.via(flow).toMat(sink)(Keep.right)

    // Using runWith will always give the materialized values
    // of the stages added by runWith() itself
    val r4: Future[Int] = source.via(flow).runWith(sink) //return the value of sink
    val r5: Promise[Option[Int]] = flow.to(sink).runWith(source) //return the value of source
    val r6: (Promise[Option[Int]], Future[Int]) = flow.runWith(source, sink) //retutrn values of both source and sink

    // Using more complext combinations
    val r7: RunnableGraph[(Promise[Option[Int]], Cancellable)] =
      source.viaMat(flow)(Keep.both).to(sink)

    val r8: RunnableGraph[(Promise[Option[Int]], Future[Int])] =
      source.via(flow).toMat(sink)(Keep.both)

    val r9: RunnableGraph[((Promise[Option[Int]], Cancellable), Future[Int])] =
      source.viaMat(flow)(Keep.both).toMat(sink)(Keep.both)

    val r10: RunnableGraph[(Cancellable, Future[Int])] =
      source.viaMat(flow)(Keep.right).toMat(sink)(Keep.both)

    // It is also possible to map over the materialized values. In r9 we had a
    // doubly nested pair, but we want to flatten it out
    val r11: RunnableGraph[(Promise[Option[Int]], Cancellable, Future[Int])] =
      r9.mapMaterializedValue {
        case ((promise, cancellable), future) =>
          (promise, cancellable, future)
      }

    // Now we can use pattern matching to get the resulting materialized values
    val (promise, cancellable, future) = r11.run()

    // Type inference works as expected
    promise.success(None)
    cancellable.cancel()
    
    import ExecutionContext.Implicits.global
    future.map(_ + 3)

    // The result of r11 can be also achieved by using the Graph API
    val r12: RunnableGraph[(Promise[Option[Int]], Cancellable, Future[Int])] =
      RunnableGraph.fromGraph(GraphDSL.create(source, flow, sink)((_, _, _)) { implicit builder =>
        (src, f, dst) =>
          import GraphDSL.Implicits._
          src ~> f ~> dst
          ClosedShape
      })

  }
  
}
