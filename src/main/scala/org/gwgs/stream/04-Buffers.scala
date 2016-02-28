package org.gwgs

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ClosedShape , Attributes, OverflowStrategy}
import akka.stream.scaladsl._
import scala.concurrent.duration._
import scala.util.Random
  

object Buffers {

  case class Tick()
  case class Job()

  /*
   * To run this, comment out system.shutdown in Main to allow the tick to contunue
   * 
   * Akka Streams use Internal buffer to reduce the cost of crossing elements through the asynchronous boundary.
   *
   * Here is an example of a code that demonstrate some of the issues caused by internal buffers.  Running this,
   * one would expect the number 3 to be printed in every 3 seconds. What is being printed is different though, 
   * we will see the number 1. The reason is the internal buffer of ZipWith which is by default 16 elements large,
   * and prefetches elements before the ZipWith starts consuming them (conflate will only work when downstream is slower). 
   * It is possible to fix this issue by changing the buffer size of ZipWith (or the whole graph) to 1. We will still see
   * a leading 1 though which is caused by an initial prefetch of the ZipWith element. 
   */
  def internalBuffers(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    
    val closed = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

//      val zipper = b.add(ZipWith[Tick, Int, Int]((tick, count) => count))
      val zipper = b.add(ZipWith[Tick, Int, Int]((tick, count) => count).withAttributes(Attributes.inputBuffer(initial = 1, max = 1)))

      Source.tick(initialDelay = 3.second, interval = 3.second, Tick()) ~> zipper.in0

      Source.tick(initialDelay = 1.second, interval = 1.second, "message!")
        .conflateWithSeed(seed = (_) => 1)((count, _) => count + 1) ~> zipper.in1
        
      zipper.out ~> Sink.foreach(println)

      ClosedShape
    })
  
    closed.run()

  }
  
  /*
   * explicitly define bufffer
   */
  def userDefinedBuffers(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val jobs: Source[Job, Unit] = null
    
    /*
     * ensure that 1000 jobs (but not more) are dequeued from an external (imaginary)
     * system and queued up locally in memory
     */
    jobs.buffer(1000, OverflowStrategy.backpressure)
    
    /*
     * queue up 1000 jobs locally, dropping one element from the tail (youngest waiting job)
     * of the buffer to make room for a new element, in the cases where the longest waiting
     * job will be the first to be executed
     */
    jobs.buffer(1000, OverflowStrategy.dropTail)
    
    /*
     * queue up 1000 jobs locally, any new element will be dropped without enqueueing it
     * to the buffer at all.
     */
    jobs.buffer(1000, OverflowStrategy.dropNew)
    
    /*
     * queue up 1000 jobs locally, dropping one element from the head (oldest waiting job)
     * of the buffer to make room for a new element, in the case jobs are expected to be
     * resent if not processed in a certain period.  The oldest element will be retransmitted, 
     * (a retransmitted duplicate might be already in the queue).
     */
    jobs.buffer(1000, OverflowStrategy.dropHead)
    
    /*
     * drops all the 1000 jobs it has enqueued locally once the buffer gets full.  This
     * aggressive strategy is useful when dropping jobs is preferred rather than delaying jobs.
     */
    jobs.buffer(1000, OverflowStrategy.dropBuffer)
    
    /*
     * enforce that source cannot have more than 1000 queued jobs, otherwise treat it as flooding
     * and terminate the connection. This is achievable by the error strategy which simply fails
     * the stream once the buffer gets full.
     */
    jobs.buffer(1000, OverflowStrategy.fail)

  }
  
  /*
   * When a fast producer can not be informed to slow down by backpressure or some other signal, 
   * conflate combines elements from a producer until a demand signal comes from a consumer, or
   * conflate randomly drops elements when consumer is not able to keep up with the producer.
   */
  def rateTransformation(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val statsFlow = Flow[Double]
      .conflateWithSeed(Seq(_))(_ :+ _)
      .map { s =>
        val μ = s.sum / s.size
        val se = s.map(x => Math.pow(x - μ, 2))
        val σ = Math.sqrt(se.sum / se.size)
        (σ, μ, s.size)
      }
      
//    val p = 0.01
//    val sampleFlow = Flow[Double]
//      .conflate(Seq(_)) {
//        case (acc, elem) if Random.nextDouble < p => acc :+ elem
//        case (acc, _)                             => acc
//      }.mapConcat(identity)
  }
  
  /*
   * Expand helps to deal with slow producers which are unable to keep up with the demand coming
   * from consumers. Expand allows to extrapolate a value to be sent as an element to a consumer.
   * 
   * Expand also allows to keep some state between demand requests from the downstream. Leveraging this,
   * here is a flow that tracks and reports a drift between fast consumer and slow producer.
   */
  def expand(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    val lastFlow = Flow[Double].expand(Iterator.continually(_))
    
    val driftFlow = Flow[Double].expand(i => Iterator.from(0).map(i -> _))
  }

}
