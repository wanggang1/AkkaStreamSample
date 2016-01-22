package org.gwgs

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import scala.concurrent.{ ExecutionContext, Future }

object QuickStart {

  val akkaTag = Hashtag("#akka")
  
  val tweets: Source[Tweet, Unit] = Source(1 to 10).map(i => Tweet(Author(s"$i $i@gmail.com"), 0L, s"Tweet $i #akka #scala"))
  
  /////////////////// Materialized values /////////////////////////////
  def run(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import ExecutionContext.Implicits.global
    val count: Flow[Tweet, Int, Unit] = Flow[Tweet].map(_ => 1)
 
    val sumSink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

    val counterGraph: RunnableGraph[Future[Int]] =
      tweets
        .via(count)
        .toMat(sumSink)(Keep.right) //Keep.right -> Mat type of the Sink, Keep.left -> Mat type of the Source???

    //materialize
    //Materialization is the process of allocating all resources needed to run the
    //computation described by a Flow (in Akka Streams this will often involve starting up Actors)
    val sum: Future[Int] = counterGraph.run()

    sum.foreach(c => println(s"Total tweets processed: $c"))
  }
  
}


final case class Author(handle: String)
 
final case class Hashtag(name: String)
 
final case class Tweet(author: Author, timestamp: Long, body: String) {
  def hashtags: Set[Hashtag] =
    body.split(" ").collect { case t if t.startsWith("#") => Hashtag(t) }.toSet
}
