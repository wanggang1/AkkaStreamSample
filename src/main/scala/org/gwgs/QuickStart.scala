package org.gwgs

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, IOResult, ThrottleMode}
import akka.util.ByteString
import akka.{Done, NotUsed}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object QuickStart {

  val akkaTag = Hashtag("#akka")
  val tweets: Source[Tweet, NotUsed] = Source(1 to 10).map(i => Tweet(Author(s"$i $i@gmail.com"), 0L, s"Tweet $i #akka #scala"))

  /**
    * reusable piece that can write string to file
    *
    * when chaining operations on a Source or Flow, the type
    * of the auxiliary information—called the “materialized value”
    * is given by the leftmost starting point; since we want to
    * retain what the FileIO.toPath sink has to offer, we need to
    * say Keep.right).
    */
  private def lineSink(filename: String): Sink[String, Future[IOResult]] =
    Flow[String]
      .map(s => ByteString(s + "\n"))
      .toMat(FileIO.toPath(Paths.get(filename)))(Keep.right)
      
  /////////////////// Materialized values /////////////////////////////
  def run(implicit system: ActorSystem, materializer: ActorMaterializer, ec: ExecutionContext): Future[Done] = {
    
    //EXAMPLE 1
    //scan is similar to fold but is not a terminal operation, emits its current value which starts at zero
    //and then applies the current and next value to the given function f, emits the next current value.
    //it emits the zero element first then elements from Source(1 to 10), total 11 elements.
    val factorials = Source(1 to 10).scan(BigInt(1))((acc, next) => acc * next)
    
    //construct a runnable graph using FileIO sink and run
    //runWith() is a convenience method that automatically ignores the materialized value of
    //any other stages except those appended by the runWith() itself.  For instance, 
    //runWith(sink), the returned materialized value is Keep.right.
    val result: Future[IOResult] =
      factorials
        .map(num => ByteString(s"$num\n"))
        .runWith(FileIO.toPath(Paths.get("outputfiles/factorials.txt")))
    
    //abstract writing logic to a reusable piece
    val result2: Future[IOResult] =
      factorials.map(_.toString).runWith(lineSink("outputfiles/factorials2.txt"))
        
    //Print Results
    result.foreach(ior => println(s"IO Result: $ior"))
    result2.foreach(ior => println(s"IO Result 2: $ior"))
    
    //Print Results: apply back pressure with throttle
    val done: Future[Done] =
      factorials
        .zipWith(Source(0 to 10))((num, idx) => s"$idx! = $num")
        .throttle(1, 1.second, 1, ThrottleMode.shaping)
        .runForeach(println)
    
    
    //EXAMPLE 2
    val count: Flow[Tweet, Int, NotUsed] = Flow[Tweet].map(_ => 1)
    val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

    //construct runnable graph
    val counterGraph: RunnableGraph[Future[Int]] =
      tweets
        .via(count)
        .toMat(sumSink)(Keep.right) //Keep.right -> Mat type appended to the right (Sink),
                                    //Keep.left -> Mat type appended to the left (Source)

    //run the graph will materialize it
    //materialization is the process of allocating all resources needed to run the
    //computation described by a Flow (in Akka Streams this will often involve starting up Actors)
    val sum: Future[Int] = counterGraph.run()
    sum.foreach(c => println(s"Total Akka tweets processed: $c"))
    
    //EXAMPLE 3 (equivalent to EXAMPLE 2)
    val sum1: Future[Int] = tweets.map(t => 1).runWith(sumSink)
    
    //EXAMPLE 4
    //flattening sequence in stream
    //The name flatMap was consciously avoided due to its proximity with for-comprehensions and monadic composition.
    //the mapConcat requires the supplied function to return a strict collection (f:Out=>immutable.Seq[T]),
    //whereas flatMap would have to operate on streams all the way through.
    val hashtags: Source[Hashtag, NotUsed] = tweets.mapConcat(_.hashtags.toList)
    hashtags.runForeach(println)
  }
  
}


final case class Author(handle: String)
 
final case class Hashtag(name: String)
 
final case class Tweet(author: Author, timestamp: Long, body: String) {
  def hashtags: Set[Hashtag] =
    body.split(" ").collect { case t if t.startsWith("#") => Hashtag(t) }.toSet
}
