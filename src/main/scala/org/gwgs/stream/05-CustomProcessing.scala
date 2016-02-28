package org.gwgs

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Attributes, Outlet, SourceShape, Graph}
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, PushPullStage, Context, SyncDirective, TerminationDirective, DetachedStage, DetachedContext, DownstreamDirective, UpstreamDirective}
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

object CustomProcessing {
  
  /*
   * GraphStage abstraction creates arbitrary graph processing stages with any 
   * number of input or output ports, to the contrary,
   * FlowGraph.create() creates new stream processing stages by composing others
   */
  def graphStage(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    // A GraphStage is a proper Graph, just like what FlowGraph.create would return
    val sourceGraph: Graph[SourceShape[Int], NotUsed] = new NumbersSource

    // Create a Source from the Graph to access the DSL
    val mySource: Source[Int, NotUsed] = Source.fromGraph(new NumbersSource)

    // Returns 55
    val sum1: Future[Int] = mySource.take(10).runFold(0)(_ + _)
    val result1 = Await.result(sum1, 300.millis)
    println(s"Sum 1 - 10 : $result1")

    // The source is reusable. This returns 5050
    val sum2: Future[Int] = mySource.take(100).runFold(0)(_ + _)
    val result2 = Await.result(sum2, 300.millis)
    println(s"Sum 1 - 100 : $result2")
  }
  
  class NumbersSource extends GraphStage[SourceShape[Int]] {
    // Define the (sole) output port of this stage
    val out: Outlet[Int] = Outlet("NumbersSource")
    // Define the shape of this stage, which is SourceShape with the port we defined above
    override val shape: SourceShape[Int] = SourceShape(out)

    // This is where the actual (possibly stateful) logic will live
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        // All state MUST be inside the GraphStageLogic,
        // never inside the enclosing GraphStage.
        // This state is safe to access and modify from all the
        // callbacks that are provided by GraphStageLogic and the
        // registered handlers.
        private var counter = 1

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            push(out, counter)
            counter += 1
          }
        })
      }
  }

  //--------------------------------------------------------------------------------------
  
  /*
   * To extend the available transformations on a Flow or Source one can use the
   * transform() method which takes a factory function returning a Stage. 
   */
  def pushPull(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    Source(1 to 10)
    .transform(() => new Filter(_ % 2 == 0))
    .transform(() => new Duplicator())
    .transform(() => new Map(_ / 2))
    .runWith(Sink.foreach(println))
    
    Source(1 to 10).filter(_ % 2 != 0)
    .transform(() => new Duplicator()).map(identity)
    .runWith(Sink.foreach(println))
  }
  
  /*
   * Stages come in different flavors. The most elementary transformation stage is
   * the PushPullStage which can express a large class of algorithms working on streams.
   * A PushPullStage can be illustrated as a box with two "input" and two "output ports".
   * The "input ports" are implemented as event handlers onPush(elem,ctx) and onPull(ctx)
   * while "output ports" correspond to methods on the Context object that is passed as
   * a parameter to the event handlers. By calling exactly one "output port" method we wire
   * up these four ports in various ways which we demonstrate shortly.
   * 
   * The callbacks (onPush, onPull) are never called concurrently. The state encapsulated by
   * this class can be safely modified from these callbacks, without any further synchronization.
   *
   * There is one very important rule to remember when working with a Stage. Exactly one
   * method should be called on the currently passed Context exactly once and as the last
   * statement of the handler where the return type of the called method matches the expected
   * return type of the handler. Any violation of this rule will almost certainly result
   * in unspecified behavior 
   */
  
  /*
   * Map is a typical example of a one-to-one transformation of a stream
   */
  class Map[A, B](f: A => B) extends PushPullStage[A, B] {
    //input port
    override def onPush(elem: A, ctx: Context[B]): SyncDirective =
      //output port
      ctx.push(f(elem))

    //input port
    override def onPull(ctx: Context[B]): SyncDirective =
      //output port
      ctx.pull()
  }
  
  /*
   * Filter is a many-to-one stage.  If the given predicate matches the current
   * element it is propagated downwards, otherwise upstream is signaled with 
   * ctx.pull() to get the new element.
   */
  class Filter[A](p: A => Boolean) extends PushPullStage[A, A] {
    override def onPush(elem: A, ctx: Context[A]): SyncDirective =
      if (p(elem)) ctx.push(elem)
      else ctx.pull()

    override def onPull(ctx: Context[A]): SyncDirective =
      ctx.pull()
  }

  /*
   *  one-to-many transformation, the Duplicator stage that emits every upstream
   *  element twice downstream 
   */
  class Duplicator[A]() extends PushPullStage[A, A] {
    private var lastElem: A = _
    private var oneLeft = false

    /*
     * this is triggered by the upstream push action, synchronized with onPull of 
     * this PushPullStage (enforced by return type SyncDirective)
     * 
     * the state is safe because upsteam would not issue the next push action unless the 
     * unPull event in this PullPushStage signaling it with ctx.pull() call
     */
    override def onPush(elem: A, ctx: Context[A]): SyncDirective = {
      lastElem = elem
      oneLeft = true
      ctx.push(elem) //trigger the onPush event downstream
    }

    /*
     * this is triggered by the downstream pull action, synchronized with onPush of 
     * this PushPullStage (enforced by return type SyncDirective)
     */
    override def onPull(ctx: Context[A]): SyncDirective =
      if (!ctx.isFinishing) {
        if (oneLeft) {
          oneLeft = false
          ctx.push(lastElem) //trigger the onPush event downstream
        } else
          ctx.pull() //trigger onPull event upstream
      } else {
        if (oneLeft) ctx.pushAndFinish(lastElem)
        else ctx.finish()
      }

    /*
     * Completion handling usually (but not exclusively) comes into the picture
     * when processing stages need to emit a few more elements after their upstream
     * source has been completed.
     * 
     * After calling absorbTermination() the onPull() handler will be called eventually,
     * and at the same time ctx.isFinishing will return true, indicating that ctx.pull()
     * cannot be called anymore.  Now we are free to emit additional elementss and call
     * ctx.finish() or ctx.pushAndFinish() eventually to finish processing.
     */
    override def onUpstreamFinish(ctx: Context[A]): TerminationDirective =
      ctx.absorbTermination()

  }
  
  //--------------------------------------------------------------------------------------
  
  /*
   * One of the important use-cases for DetachedStage is to build buffer-like entities,
   * that allow independent progress of upstream and downstream stages when the buffer
   * is not full or empty, and slowing down the appropriate side if the buffer becomes
   * empty or full. 
   */
  class Buffer2[T]() extends DetachedStage[T, T] {
    private var buf = Vector.empty[T]
    private var capacity = 2

    private def isFull = capacity == 0
    private def isEmpty = capacity == 2

    private def dequeue(): T = {
      capacity += 1
      val next = buf.head
      buf = buf.tail
      next
    }

    private def enqueue(elem: T) = {
      capacity -= 1
      buf = buf :+ elem
    }

    override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
      if (isEmpty) {
        if (ctx.isFinishing) ctx.finish() // No more elements will arrive
        else ctx.holdDownstream() // waiting until new elements
      } else {
        val next = dequeue()
        if (ctx.isHoldingUpstream) ctx.pushAndPull(next) // release upstream
        else ctx.push(next)
      }
    }

    override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective = {
      enqueue(elem)
      if (isFull) ctx.holdUpstream() // Queue is now full, wait until new empty slot
      else {
        if (ctx.isHoldingDownstream) ctx.pushAndPull(dequeue()) // Release downstream
        else ctx.pull()
      }
    }

    override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective = {
      if (!isEmpty) ctx.absorbTermination() // still need to flush from buffer
      else ctx.finish() // already empty, finishing
    }
  }
  
}
