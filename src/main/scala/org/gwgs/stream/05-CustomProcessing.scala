package org.gwgs.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.concurrent.{Await, Future}
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

  /*
   * To extend the available transformations on a Flow or Source one can use the
   * transform() method which takes a factory function returning a Stage.
   */
  def pushPull(implicit system: ActorSystem, materializer: ActorMaterializer) = {

    Source(1 to 10)
      .via(new Filter(_ % 2 == 0))
      .via(new Duplicator())
      .via(new Map(_ / 2))
      .runWith(Sink.foreach(println))

    Source(1 to 10).filter(_ % 2 != 0)
      .via(new Duplicator())
      .runWith(Sink.foreach(println))

  }

  //--------------------------------------------------------------------------------------
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

  /*
   * Map is a typical example of a one-to-one transformation of a stream
   */
  class Map[A, B](f: A => B) extends GraphStage[FlowShape[A, B]] {

    val in = Inlet[A]("Map.in")
    val out = Outlet[B]("Map.out")

    override val shape = FlowShape.of(in, out)

    override def createLogic(attr: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            push(out, f(grab(in)))
          }
        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pull(in)
          }
        })
      }
  }

  /*
   * Filter is a many-to-one stage.  If the given predicate matches the current
   * element it is propagated downwards, otherwise upstream is signaled with
   * pull() to get the new element.
   */
  class Filter[A](p: A => Boolean) extends GraphStage[FlowShape[A, A]] {

    val in = Inlet[A]("Filter.in")
    val out = Outlet[A]("Filter.out")

    val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            if (p(elem)) push(out, elem)
            else pull(in)
          }
        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            pull(in)
          }
        })
      }
  }

  /*
   *  one-to-many transformation, the Duplicator stage that emits every upstream
   *  element twice downstream
   */
  class Duplicator[A] extends GraphStage[FlowShape[A, A]] {

    val in = Inlet[A]("Duplicator.in")
    val out = Outlet[A]("Duplicator.out")

    val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        // Again: note that all mutable state
        // MUST be inside the GraphStageLogic
        var lastElem: Option[A] = None

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            lastElem = Some(elem)
            push(out, elem)
          }

          override def onUpstreamFinish(): Unit = {
            if (lastElem.isDefined) emit(out, lastElem.get)
            complete(out)
          }

        })
        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (lastElem.isDefined) {
              push(out, lastElem.get)
              lastElem = None
            } else {
              pull(in)
            }
          }
        })
      }
  }

  //--------------------------------------------------------------------------------------

  /*
   * One of the important use-cases for DetachedStage is to build buffer-like entities,
   * that allow independent progress of upstream and downstream stages when the buffer
   * is not full or empty, and slowing down the appropriate side if the buffer becomes
   * empty or full.
   */
  class TwoBuffer[A] extends GraphStage[FlowShape[A, A]] {

    val in = Inlet[A]("TwoBuffer.in")
    val out = Outlet[A]("TwoBuffer.out")

    val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        val buffer = scala.collection.mutable.Queue[A]()
        def bufferFull = buffer.size == 2
        var downstreamWaiting = false

        override def preStart(): Unit = {
          // a detached stage needs to start upstream demand
          // itself as it is not triggered by downstream demand
          pull(in)
        }

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            buffer.enqueue(elem)
            if (downstreamWaiting) {
              downstreamWaiting = false
              val bufferedElem = buffer.dequeue()
              push(out, bufferedElem)
            }
            if (!bufferFull) {
              pull(in)
            }
          }

          override def onUpstreamFinish(): Unit = {
            if (buffer.nonEmpty) {
              // emit the rest if possible
              emitMultiple(out, buffer.toIterator)
            }
            completeStage()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (buffer.isEmpty) {
              downstreamWaiting = true
            } else {
              val elem = buffer.dequeue
              push(out, elem)
            }
            if (!bufferFull && !hasBeenPulled(in)) {
              pull(in)
            }
          }
        })
      }

  }

}
