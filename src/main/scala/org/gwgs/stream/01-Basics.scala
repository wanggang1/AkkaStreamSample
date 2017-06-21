package org.gwgs.stream

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{ActorMaterializer, ClosedShape, ThrottleMode}
import akka.stream.scaladsl._

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.util.Try

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
    // a new processing stage.  existing one is not affected.
    // Source.runWith materializes the flow and returns Sink's materialized value
    val zeroes = source.map(_ => 0)
    zeroes.runWith(sink) // 0
  
    // Convenient method, materialize the flow, getting the Sinks materialized value
    val sum1: Future[Int] = source.runWith(sink)
    val result1 = Await.result(sum1, 1 second)
    println(s"result1 = $result1")

    // Sink.runWith materializes the flow and returns Source's materialized value
    val optSource:Source[Int, Promise[Option[Int]]] = Source.maybe[Int]
    val sum2: Promise[Option[Int]] = Sink.head.runWith(optSource)
    sum2.complete(Try(Some(2)))
    val result2: Option[Int] = Await.result(sum2.future, 1 second)
    println(s"result2 = $result2")
  }
  
  /*
   * Different ways to wire up
   */
  def wireup(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    println("///////////////// Wireup /////////////////")
    
    //Do not not need materialized because sink is side-effect only and the return type is Unit???

    // Explicitly creating and wiring up a Source, Sink and Flow
    val pipeline1: RunnableGraph[NotUsed] =
      Source(1 to 6).via(Flow[Int].map(_ * 2)).to(Sink.foreach(i => println(s"Explicitly : $i")))
    pipeline1.run()
    
    // Starting from a Source
    val source1 = Source(1 to 6).map(_ * 2)
    val pipeline2: RunnableGraph[NotUsed] = source1.to(Sink.foreach(i => println(s"Start From Source : $i")))
//    pipeline2.run()
    
    // Starting from a Sink
    val sink1: Sink[Int, NotUsed] = Flow[Int].map(_ * 2).to(Sink.foreach(i => println(s"Start From Sink : $i")))
    val pipeline3: RunnableGraph[NotUsed] = Source(1 to 6).to(sink1)
//    pipeline3.run()
    
    // Broadcast to a sink inline
    val otherSink: Sink[Int, NotUsed] =
      Flow[Int].alsoTo(Sink.foreach(i => println(s"Broadcast : $i"))).to(Sink.ignore)
    val pipeline4 = Source(1 to 6).to(otherSink)
//    pipeline4.run
  }
  
  /**
    * By default Akka Streams will fuse the stream operators. The processing stages of a flow
    * or stream graph therefore will be executed within the same Actor and the results are:
    *   - no asynchronous messaging boundary (across actors) -> faster
    *   - no parallel execution, one CPY core per each fused part
    *
    * To allow for parallel processing, insert Attributes.asyncBoundary manually into flows
    * and graphs by using the method async on Source, Sink and Flow
   */
  def fusion(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    println("///////////////// Fusion /////////////////")
    //by default, flow is fused
    val flow = Flow[Int].map(_ * 2).filter(_ > 5000)

    /**
      * add fusion async boundary to enable parallel execution on map,
      * any thing steps before async will be in one actor, after async
      * will be in another
      */
    val flow_fused = Flow[Int].map(_ * 2).async.filter(_ > 5000)

    Source.fromIterator { () => Iterator from 0 }
      .via(flow)
      .take(10)
      .runForeach(println)

    Source.fromIterator { () => Iterator from 0 }
      .via(flow_fused)
      .take(10)
      .runForeach(println)
  }

  /*
   * Combining materialized values
   */
  def combine(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext) = {
    println("///////////////// Combine materialized values/////////////////")
    
    // An source that can be signalled explicitly from the outside
    val source: Source[Int, Promise[Option[Int]]] = Source.maybe[Int]

    // A flow that internally throttles elements to 1/second, and returns a Cancellable
    // which can be used to shut down the stream
    //TODO: make materialized value to be Cancellable
    //val flow: Flow[Int, Int, NotUsed] = Flow[Int].throttle(1, 1.second, 1, ThrottleMode.shaping)
    val flow: Flow[Int, Int, Cancellable] = null

    // A sink that returns the first element of a stream in the returned Future
    val sink: Sink[Int, Future[Int]] = Sink.head[Int]

    // By default, the materialized value of the leftmost stage is preserved
    val r1: RunnableGraph[Promise[Option[Int]]] = source.via(flow).to(sink)

    // Simple selection of materialized values by using Keep.right, use toMat at the stage where the mat value is wanted
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
