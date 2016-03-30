package org.gwgs

import akka.{ Done, NotUsed }
import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.{ 
  ActorMaterializer,
  ClosedShape ,
  UniformFanInShape,
  SourceShape,
  FlowShape, 
  Inlet,
  Outlet,
  Shape,
  FanInShape, 
  Graph, 
  BidiShape
}
import akka.stream.scaladsl._
import scala.concurrent.{ Await, Future }
import akka.util.ByteString
import java.nio.ByteOrder

//import scala.language.postfixOps
import scala.concurrent.duration._

/**
 * Graph
 */
object Graphs {

  def graph(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    
    //No argument with FlowGraph.create()
    val g: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      val in: Source[Int, NotUsed] = Source(1 to 10)
      val out: Sink[Int, Future[Done]] = Sink.foreach[Int]{ println(_) } //Sink.ignore

      //builder is mutable
      val bcast = builder.add(Broadcast[Int](2))
      val merge = builder.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      // ~> operator add element t- mutable builder
      import GraphDSL.Implicits._
      in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
                  bcast ~> f4 ~> merge
                  
      ClosedShape
    })

    g.run()
  }

  def graphReuse(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val topHeadSink: Sink[Int, Future[Int]] = Sink.head[Int]
    val bottomHeadSink: Sink[Int, Future[Int]] = Sink.head[Int]
    val sharedDoubler: Flow[Int, Int, NotUsed] = Flow[Int].map(_ * 2)

    //When graph is created with arguments, arguments can be retrieved inside the creation function
    val g: RunnableGraph[(Future[Int], Future[Int])] =
      RunnableGraph.fromGraph(GraphDSL.create(topHeadSink, bottomHeadSink)((_, _)) { implicit builder: GraphDSL.Builder[(Future[Int], Future[Int])] =>
        (topHS, bottomHS) =>
          val broadcast = builder.add(Broadcast[Int](2))
          
          import GraphDSL.Implicits._
          /*
          Source.single(1) ~> broadcast.in
          broadcast.out(0) ~> sharedDoubler ~> topHS.in
          broadcast.out(1) ~> sharedDoubler ~> bottomHS.in
          */
          Source.single(1) ~> broadcast ~> sharedDoubler ~> topHS
                              broadcast ~> sharedDoubler ~> bottomHS
                              
          ClosedShape
      })
  
    val (t: Future[Int], b: Future[Int]) = g.run()
    val top = Await.result(t, 300 millis)
    val bottom = Await.result(b, 300 millis)
    println(s"Top: $top, Bottom : $bottom")
  }
  
  /*
   * Constructing and combining Partial Flow Graphs
   * --Example: given 3 inputs and pick the greatest 
   */
  def partialGraph(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val pickMaxOfThree = GraphDSL.create() { implicit b =>
      val zip1 = b.add(ZipWith[Int, Int, Int](math.max _))
      val zip2 = b.add(ZipWith[Int, Int, Int](math.max _))
      
      //wire up zip1.out to 1 "in" on zip2, 2 "in"s on zip1 and the other "in" on zip2 are still open
      import GraphDSL.Implicits._
      zip1.out ~> zip2.in0

      UniformFanInShape(zip2.out, zip1.in0, zip1.in1, zip2.in1)
    }
 
    val resultSink = Sink.head[Int]

    //When graph is created with an argument, this argument can be retrieved inside the creation function
    val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b =>
      sink =>
        // importing the partial graph will return its shape (inlets & outlets)
        val pm3 = b.add(pickMaxOfThree)

        import GraphDSL.Implicits._
        /*
        Source.single(1) ~> pm3.in(0)
        Source.single(2) ~> pm3.in(1)
        Source.single(3) ~> pm3.in(2)
        pm3.out ~> sink.in
        */
        Source.single(1) ~> pm3 
        Source.single(2) ~> pm3 ~> sink
        Source.single(3) ~> pm3
        
        ClosedShape
    })
 
    val max: Future[Int] = g.run()
    val result = Await.result(max, 300 millis)
    println(s"Max : $result")
  }
  
  /*
   * pack complex graphs inside Source.  Similar for Sink, use SinkShap in which the provided value must be an Inlet[T]
   */
  def simpleSource(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val pairs = Source.fromGraph(GraphDSL.create() { implicit b =>
      // prepare graph elements
      val zip = b.add(Zip[Int, Int]())
      def ints = Source.fromIterator(() => Iterator.from(1))

      // connect the graph
      import GraphDSL.Implicits._
      ints.filter(_ % 2 != 0) ~> zip.in0
      ints.filter(_ % 2 == 0) ~> zip.in1

      // expose port
      SourceShape(zip.out)
    })
 
    // use pairs as a simple source
    val firstPair: Future[(Int, Int)] = pairs.runWith(Sink.head)
    val result = Await.result(firstPair, 300 millis)
    println(s"First Pair : $result")
  }
  
  /*
   * pack complex graphs inside Flow, need to expose both an inlet and an outlet
   */
  def simpleFlow(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val pairUpWithToString =
      Flow.fromGraph(GraphDSL.create() { implicit b =>
        // prepare graph elements
        val broadcast = b.add(Broadcast[Int](2))
        val zip = b.add(Zip[Int, String]())

        // connect the graph
        import GraphDSL.Implicits._
        broadcast.out(0).map(identity) ~> zip.in0
        broadcast.out(1).map(_.toString) ~> zip.in1

        // expose ports
        FlowShape(broadcast.in, zip.out)
      })
  
    /*
     * run flow pairUpWithToString
     */
    val firstPair: (NotUsed, Future[(Int, String)]) = pairUpWithToString.runWith(Source(List(1)), Sink.head)
    val result = Await.result(firstPair._2, 300 millis)
    println(s"Pair (Int, String) : $result")  
  }
  
  /*
   * Simplified API to combine sources or sinks with junctions like: Broadcast[T],
   * Balance[T], Merge[In] and Concat[A] without the need for using the Graph DSL.
   * The combine method takes care of constructing the necessary graph underneath.
   */
  def simplifedAPI(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    /*
     * Merge 2 sources
     */
    val sourceOne = Source(List(1))
    val sourceTwo = Source(List(2))
    val merged = Source.combine(sourceOne, sourceTwo)(Merge(_))
    
    val mergedResult: Future[Int] = merged.runWith(Sink.fold(0)(_ + _))
    val result = Await.result(mergedResult, 300 millis)
    println(s"Sum of merged sources : $result")
    
    /*
     * broadcast to 2 seperate sink
     */
//    var actorRef: ActorRef = null
//    val sendRmotely = Sink.actorRef(actorRef, "Done")
//    val localProcessing = Sink.foreach[Int](_ => /* do something usefull */ ())
//    val sink = Sink.combine(sendRmotely, localProcessing)(Broadcast[Int](_))
//    
//    Source(List(0, 1, 2)).runWith(sink)
  }
  
  /*
   * build reusable, encapsulated components of arbitrary input and
   * output ports using the graph DSL
   */
  def customizeShape(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import ArbitraryShape._
    
    val worker1 = Flow[String].map("worker 1 " + _)
    val worker2 = Flow[String].map("worker 2 " + _)

    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
      val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

      import GraphDSL.Implicits._
      Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
      Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

      priorityPool1.resultsOut ~> priorityPool2.jobsIn
      Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

      priorityPool2.resultsOut ~> Sink.foreach(println)
      
      ClosedShape
    })
  
   g.run()
  }

  /*
   * add a framing protocol that attaches a length header to outgoing data and parses
   * incoming frames back into the original octet stream chunks
   * 
   * construct protocol stack
   *         +------------------------------------+
   *         | stack                              |
   *         |                                    |
   *         |  +-------+            +---------+  |
   *    ~>   O~~o       |     ~>     |         o~~O    ~>
   * Message |  | codec | ByteString | framing |  | ByteString
   *    <~   O~~o       |     <~     |         o~~O    <~
   *         |  +-------+            +---------+  |
   *         +------------------------------------+
   */
  def bidiFlow(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import BiDirectionalFlow_codec._
    import BiDirectionalFlow_framing._
    
    val stack = codec.atop(framing)
 
    // test it by plugging it into its own inverse and closing the right end
    val pingpong = Flow[Message].collect { case Ping(id) => Pong(id) }
    val flow = stack.atop(stack.reversed).join(pingpong)
    val resultFuture = Source((0 to 9).map(Ping)).via(flow).limit(20).runWith(Sink.seq)

    val result = Await.result(resultFuture, 1 second)
    println(s"Pongs : $result")
  }
  
  /*
   * Feed back the materialized value of a Graph (partial, closed or backing a Source, Sink, Flow or BidiFlow)
   */
  def materializedValue(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import GraphDSL.Implicits._

    //using builder.materializedValue, which gives an Outlet that can be used in the graph as an ordinary source or outlet
    //--graph is created with Sink.fold as argument, which is then available inside the creation function
    val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) {
      implicit builder ⇒
        fold ⇒
          FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
    })
  
    val runnable: RunnableGraph[Future[Int]] = Source(1 to 3).viaMat(foldFlow)(Keep.right).to(Sink.foreach(i => println(s"Got $i")))
    
    // materialize the flow and get the value of the FoldSink
    val sum: Future[Int] = runnable.run()
    val result = Await.result(sum, 1 second)
    println(s"result = $result")
  }
  
}

/**
 * ArbitraryShape
 */
object ArbitraryShape {
  
  //A shape represents the input and output ports of a reusable processing module
  case class PriorityWorkerPoolShape[In, Out](
    jobsIn: Inlet[In],
    priorityJobsIn: Inlet[In],
    resultsOut: Outlet[Out]) extends Shape {

    // It is important to provide the list of all input and output
    // ports with a stable order. Duplicates are not allowed.
    override val inlets: scala.collection.immutable.Seq[Inlet[_]] = jobsIn :: priorityJobsIn :: Nil
    override val outlets: scala.collection.immutable.Seq[Outlet[_]] = resultsOut :: Nil

    // A Shape must be able to create a copy of itself. Basically
    // it means a new instance with copies of the ports
    override def deepCopy() = PriorityWorkerPoolShape(
      jobsIn.carbonCopy(),
      priorityJobsIn.carbonCopy(),
      resultsOut.carbonCopy())

    // A Shape must also be able to create itself from existing ports
    override def copyFromPorts(
      inlets: scala.collection.immutable.Seq[Inlet[_]],
      outlets: scala.collection.immutable.Seq[Outlet[_]]) = {
      assert(inlets.size == this.inlets.size)
      assert(outlets.size == this.outlets.size)
      // This is why order matters when overriding inlets and outlets.
      PriorityWorkerPoolShape[In, Out](inlets(0).as[In], inlets(1).as[In], outlets(0).as[Out])
    }
  }

  import FanInShape.Name
  import FanInShape.Init

  //Since PriorityWorkerPoolShape has two input ports and one output port,
  //FanInShape DSL is used to define this custom shape.
  class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool"))
    extends FanInShape[Out](_init) {

    protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

    val jobsIn = newInlet[In]("jobsIn")
    val priorityJobsIn = newInlet[In]("priorityJobsIn")
    // Outlet[Out] with name "out" is automatically created
  }

  object PriorityWorkerPool {
    def apply[In, Out](
        worker: Flow[In, Out, Any],
        workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = {

      GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val priorityMerge = b.add(MergePreferred[In](1)) //define 1 normal port in addition to the preferred port
        val balance = b.add(Balance[In](workerCount))
        val resultsMerge = b.add(Merge[Out](workerCount))

        // After merging priority and ordinary jobs, we feed them to the balancer
        priorityMerge ~> balance

        // Wire up each of the outputs of the balancer to a worker flow
        // then merge them back (worker flow is reusable)
        for (i <- 0 until workerCount)
          balance.out(i) ~> worker ~> resultsMerge.in(i)

        // We now expose the input ports of the priorityMerge and the output
        // of the resultsMerge as our PriorityWorkerPool ports
        // -- all neatly wrapped in our domain specific Shape
        PriorityWorkerPoolShape(
          jobsIn = priorityMerge.in(0),
          priorityJobsIn = priorityMerge.preferred,
          resultsOut = resultsMerge.out)
      }
    }
  }

}


/**
 * BiDirectionalFlow
 * 
 *        +------+
 *  In1 ~>|      |~> Out1
 *        | bidi |
 * Out2 <~|      |<~ In2
 *        +------+
 *        
 * codec stage that serializes outgoing messages and deserializes incoming octet streams
 */
object BiDirectionalFlow_codec {
  sealed trait Message
  case class Ping(id: Int) extends Message
  case class Pong(id: Int) extends Message

  def messageToBytes(msg: Message): ByteString = {
    implicit val order = ByteOrder.LITTLE_ENDIAN
    msg match {
      case Ping(id) => ByteString.newBuilder.putByte(1).putInt(id).result()
      case Pong(id) => ByteString.newBuilder.putByte(2).putInt(id).result()
    }
  }

  def bytesToMessage(bytes: ByteString): Message = {
    implicit val order = ByteOrder.LITTLE_ENDIAN
    val it = bytes.iterator
    it.getByte match {
      case 1     => Ping(it.getInt)
      case 2     => Pong(it.getInt)
      case other => throw new RuntimeException(s"parse error: expected 1|2 got $other")
    }
  }

  val codecVerbose: BidiFlow[Message, ByteString, ByteString, Message, NotUsed] =
    BidiFlow.fromGraph(GraphDSL.create() { implicit b =>
      // construct and add the top flow, going outbound
      val outbound = b.add(Flow[Message].map(messageToBytes))
      
      // construct and add the bottom flow, going inbound
      val inbound = b.add(Flow[ByteString].map(bytesToMessage))
      
      // fuse them together into a BidiShape
      BidiShape.fromFlows(outbound, inbound)
    })

  // functional 1:1 transformation , this is the same as the above
  val outboundFun = messageToBytes _
  val inboundFun = bytesToMessage _
  val codec: BidiFlow[Message, ByteString, ByteString, Message, NotUsed] =
    BidiFlow.fromFunctions(outboundFun, inboundFun)

}

/*
 *             +---------+  
 *        ~>   o         o   ~>
 *  ByteString | framing |   ByteString
 *   		  <~   o         o   <~
 *             +---------+  
 */
object BiDirectionalFlow_framing {
  import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
  import akka.stream.Attributes
  implicit val order = ByteOrder.LITTLE_ENDIAN

  /**
   * framing stage is a little more involved since reversing a framing protocol means
   * that any received chunk of bytes may correspond to zero or more messages. This
   * is best implemented using a GraphStage
   */
  class FrameParser extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in = Inlet[ByteString]("FrameParser.in")
    val out = Outlet[ByteString]("FrameParser.out")
    override val shape = FlowShape.of(in, out)
 
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
 
      // this holds the received but not yet parsed bytes
      var stash = ByteString.empty
      // this holds the current message length or -1 if at a boundary
      var needed = -1
 
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (isClosed(in)) run()
          else pull(in)
        }
      })
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val bytes = grab(in)
          stash = stash ++ bytes
          run()
        }
 
        override def onUpstreamFinish(): Unit = {
          if (stash.isEmpty) completeStage()
          // wait with completion and let run() complete when the
          // rest of the stash has been sent downstream
        }
      })
 
      private def run(): Unit = {
        if (needed == -1) {
          // are we at a boundary? then figure out next length
          if (stash.length < 4) {
            if (isClosed(in)) completeStage()
            else pull(in)
          } else {
            needed = stash.iterator.getInt
            stash = stash.drop(4)
            run() // cycle back to possibly already emit the next chunk
          }
        } else if (stash.length < needed) {
          // we are in the middle of a message, need more bytes,
          // or have to stop if input closed
          if (isClosed(in)) completeStage()
          else pull(in)
        } else {
          // we have enough to emit at least one message, so do it
          val emit = stash.take(needed)
          stash = stash.drop(needed)
          needed = -1
          push(out, emit)
        }
      }
    }
  }
  
  // outbound: adding the length header to ByteString
  def addLengthHeader(bytes: ByteString) = {
    val len = bytes.length
    ByteString.newBuilder.putInt(len).append(bytes).result()
  }

  val framing: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] =
    BidiFlow.fromGraph(GraphDSL.create() { b =>
      val outbound = b.add(Flow[ByteString].map(addLengthHeader))
      val inbound = b.add(Flow[ByteString].via(new FrameParser))
      BidiShape.fromFlows(outbound, inbound)
    })

}
