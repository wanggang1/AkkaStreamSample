package org.gwgs

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

object Modularity {

  def runnableGraph(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val nestedSource =
      Source.single(0) // An atomic source
        .map(_ + 1) // an atomic processing stage
        .named("nestedSource") // wraps up the current Source and gives it a name

    val nestedFlow =
      Flow[Int].filter(_ != 0) // an atomic processing stage
        .map(_ - 2) // another atomic processing stage
        .named("nestedFlow") // wraps up the Flow, and gives it a name

    val nestedSink =
      nestedFlow.toMat(Sink.fold(0)(_ + _))(Keep.right) // wire an atomic sink to the nestedFlow, and make the materialized value ready for use
        .named("nestedSink") // wrap it up

    // Create a RunnableGraph
    val runnableGraph = nestedSource.toMat(nestedSink)(Keep.right) // return the materialized value
    
    val sum: Future[Int] = runnableGraph.run()
    val result = Await.result(sum, 1 second)
    println(s"result = $result")
  }
  
  /*
   * Use implicit port numbering feature by importing Source s, Sink s and Flow s explicitly;
   * to make the graph more readable and similar to the diagram 
   */
  def complexSystem(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import GraphDSL.Implicits._
    
    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val A: Outlet[Int]                  = builder.add(Source.single(0)).out
      val B: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
      val C: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
      val D: FlowShape[Int, Int]          = builder.add(Flow[Int].map(_ + 1))
      val E: UniformFanOutShape[Int, Int] = builder.add(Balance[Int](2))
      val F: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
      val G: Inlet[Any]                   = builder.add(Sink.foreach(println)).in

                    C     <~      F
      A  ~>  B  ~>  C     ~>      F
             B  ~>  D  ~>  E  ~>  F
                           E  ~>  G

      ClosedShape
    })
  }
  
  /*
   * Use the ports explicitly, then no need to import linear stages of Source/Flow/Sink via add()
   */
  def complexSystem2(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import GraphDSL.Implicits._
    
    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val B = builder.add(Broadcast[Int](2))
      val C = builder.add(Merge[Int](2))
      val E = builder.add(Balance[Int](2))
      val F = builder.add(Merge[Int](2))

      Source.single(0) ~> B.in; B.out(0) ~> C.in(1); C.out ~> F.in(0)
      C.in(0) <~ F.out

      B.out(1).map(_ + 1) ~> E.in; E.out(0) ~> F.in(1)
      E.out(1) ~> Sink.foreach(println)
      ClosedShape
    })
  }
  
  /*
   * Similar to above, but remove Source and Sink, create a reusable component with the graph DSL
   * 
   * Modularization!!!
   */
  def partialGraph(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import GraphDSL.Implicits._
    val partial = GraphDSL.create() { implicit builder =>
      val B = builder.add(Broadcast[Int](2))
      val C = builder.add(Merge[Int](2))
      val E = builder.add(Balance[Int](2))
      val F = builder.add(Merge[Int](2))

                                       C  <~  F
      B  ~>                            C  ~>  F
      B  ~>  Flow[Int].map(_ + 1)  ~>  E  ~>  F
      FlowShape(B.in, E.out(1))
    }.named("partial")
    
    //E is a balnacer.  The single element goes to the first available downstream F.
    //Therefore, no element goes to E's 2nd downstream port(out port).  Nothing prints
    val runnable = Source.single(0).via(partial).to(Sink.foreach(i => println(s"Got $i")))
    runnable.run()
    
//    val runnable = Source.single(0).via(partial).toMat(Sink.head)(Keep.right)
//    val single: Future[Int] = runnable.run()
//    val result = Await.result(single, 1 second)
//    println(s"result = $result")

    // Convert the partial graph of FlowShape to a Flow to get
    // access to the fluid DSL (for example to be able to call .filter())
    val flow = Flow.fromGraph(partial)

    // Simple way to create a graph backed Source
    val source = Source.fromGraph( GraphDSL.create() { implicit builder =>
      val merge = builder.add(Merge[Int](2))
      Source.single(0)      ~> merge
      Source(List(2, 3, 4)) ~> merge

      // Exposing exactly one output port
      SourceShape(merge.out)
    })

    // Building a Sink with a nested Flow, using the fluid DSL
    val sink = {
      val nestedFlow = Flow[Int].map(_ * 2).drop(1).named("nestedFlow")
      nestedFlow.toMat(Sink.head)(Keep.right)
    }

    // Putting all together
    val closed = source.via(flow.filter(_ > 1)).toMat(sink)(Keep.right)
    val single: Future[Int] = closed.run()
    val result = Await.result(single, 1 second)
    println(s"result = $result")
  }
  
  /*
   * Each module inherits the inputBuffer attribute from its enclosing parent,
   * unless it has the same attribute explicitly set.
   * Materializer sets the top level deffauts
   */
  def attributes(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import Attributes._
    val nestedSource =
      Source.single(0)
        .map(_ + 1)
        .named("nestedSource") // Wrap, no inputBuffer set

    val nestedFlow =
      Flow[Int].filter(_ != 0)
        .via(Flow[Int].map(_ - 2).withAttributes(inputBuffer(4, 4))) // override inpo=utBuffer
        .named("nestedFlow") 

    val nestedSink =
      nestedFlow.to(Sink.fold(0)(_ + _)) // wire an atomic sink to the nestedFlow
        .withAttributes(name("nestedSink") and inputBuffer(3, 3)) // override inpo=utBuffer again
  }
  
}
