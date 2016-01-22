package org.gwgs.stream

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.testkit.TestProbe

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.Failure


object TestStream {

  /*
   * Testing a custom sink can be as simple as attaching a source that emits elements
   * from a predefined collection, running a constructed test flow and asserting on
   * the results that sink produced.
   * 
   * Same as testing source and flow
   */
  def testBuildIns(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sinkUnderTest = Flow[Int].map(_ * 2).toMat(Sink.fold(0)(_ + _))(Keep.right)
    val future = Source(1 to 4).runWith(sinkUnderTest)
    val result = Await.result(future, 100.millis)
    assert(result == 20)
    
    val sourceUnderTest = Source.repeat(1).map(_ * 2) //infinite stream
    val future1 = sourceUnderTest.grouped(10).runWith(Sink.head) //group the infinite stream by 10 and take the head
    val result1 = Await.result(future1, 100.millis)
    assert(result1 == Seq.fill(10)(2))
    
    val flowUnderTest = Flow[Int].takeWhile(_ < 5)
    val future2 = Source(1 to 10).via(flowUnderTest).runWith(Sink.fold(Seq.empty[Int])(_ :+ _))
    val result2 = Await.result(future2, 100.millis)
    assert(result2 == (1 to 4))
  }
 
  /*
   * materialize stream to a Future and then use pipe pattern to pipe the result
   * of that future to the probe.
   */
  def testViaAkkaTestkit1(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import system.dispatcher
    import akka.pattern.pipe

    val sourceUnderTest = Source(1 to 4).grouped(2)

    val probe = TestProbe()
    val future = sourceUnderTest.grouped(2).runWith(Sink.head)
    future pipeTo probe.ref
    
    probe.expectMsg(100.millis, Seq(Seq(1, 2), Seq(3, 4)))
  }
  
  /*
   * use Sink.actorRef that provides control over received elements.  Sink.actorRef sends
   * all incoming elements to a given TestProbe ActorRef.  Now we can use assertion methods
   * on TestProbe and expect elements one by one as they arrive. We can also assert stream
   * completion by expecting onCompleteMessage which was given to Sink.actorRef.
   */
  def testViaAkkaTestkit2(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    case object Tick
    val sourceUnderTest = Source.tick(0.seconds, 200.millis, Tick)

    val probe = TestProbe()
    val cancellable = sourceUnderTest.to(Sink.actorRef(probe.ref, "onComplete message")).run()

    probe.expectMsg(1.second, Tick)
    probe.expectNoMsg(100.millis)
    probe.expectMsg(200.millis, Tick)
    cancellable.cancel()
    probe.expectMsg(200.millis, "onComplete message")
  }
  
  /*
   * use Source.actorRef and have full control over elements to be sent.
   */
  def testViaAkkaTestkit3(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sinkUnderTest = Flow[Int].map(_.toString).toMat(Sink.fold("")(_ + _))(Keep.right)
 
    val (ref, future) = Source.actorRef(8, OverflowStrategy.fail)
      .toMat(sinkUnderTest)(Keep.both).run()

    ref ! 1
    ref ! 2
    ref ! 3
    ref ! akka.actor.Status.Success("done")

    val result = Await.result(future, 100.millis)
    assert(result == "123")
  }
  
  /*
   * A sink returned by TestSink.probe allows manual control over demand and assertions
   * over elements coming downstream.
   */
  def testViaStreamTestkit1(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sourceUnderTest = Source(1 to 4).filter(_ % 2 == 0).map(_ * 2)
 
    sourceUnderTest
      .runWith(TestSink.probe[Int])
      .request(2)
      .expectNext(4, 8)
      .expectComplete()
  }
  
  /*
   * A source returned by TestSource.probe can be used for asserting demand or
   * controlling when stream is completed or ended with an error.
   */
  def testViaStreamTestkit2(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sinkUnderTest = Sink.cancelled
 
    TestSource.probe[Int]
      .toMat(sinkUnderTest)(Keep.left)
      .run()
      .expectCancellation()
  }
  
  /*
   * inject exceptions and test sink behaviour on error conditions.
   */
  def testViaStreamTestkit3(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val sinkUnderTest = Sink.head[Int]
 
    val (probe, future) = TestSource.probe[Int]
      .toMat(sinkUnderTest)(Keep.both)
      .run()
    probe.sendError(new Exception("boom"))

    Await.ready(future, 100.millis)
    val Failure(exception) = future.value.get
    assert(exception.getMessage == "boom")
  }
  
  /*
   * Test source and sink can be used together in combination when testing flows.
   */
  def testViaStreamTestkit4(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import system.dispatcher
    
    val flowUnderTest = Flow[Int].mapAsyncUnordered(2) { sleep =>
      akka.pattern.after(10.millis * sleep, using = system.scheduler)(Future.successful(sleep))
    }

    val (pub, sub) = TestSource.probe[Int]
      .via(flowUnderTest)
      .toMat(TestSink.probe[Int])(Keep.both)
      .run()

    sub.request(n = 3)
    pub.sendNext(3)
    pub.sendNext(2)
    pub.sendNext(1)
    sub.expectNextUnordered(1, 2, 3)

    pub.sendError(new Exception("Power surge in the linear subroutine C-47!"))
    val ex = sub.expectError()
    assert(ex.getMessage.contains("C-47"))
  }
}
