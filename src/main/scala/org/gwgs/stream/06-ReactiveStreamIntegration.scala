package org.gwgs.stream

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.gwgs.{Author, QuickStart, Tweet}

object ReactiveStreamIntegration {

  /*
   * Reactive Streams defines a standard for asynchronous stream processing with
   * non-blocking back pressure. It makes it possible to plug together stream libraries
   * that adhere to the standard. Akka Streams is one such library.  Others are RxJava,
   * Slick, Reactor (1.1+), Ratpack, etc.
   */
  def otherStream(implicit materializer: ActorMaterializer) = {
    import QuickStart.akkaTag
    
    val authors: Flow[Tweet,Author,NotUsed] = Flow[Tweet]
      .filter(_.hashtags.contains(akkaTag))
      .map(_.author)

    /*
     * The Publisher is used as an input Source to the flow and the Subscriber is
     * used as an output Sink.
     */      
    val tweets = new TweetPublisher
    val storage = new AuthorSubscriber

    Source.fromPublisher(tweets).via(authors)
      .to(Sink.fromSubscriber(storage)).run()
      
    //There are other details on the integration in the akka stream doc!!!!
  }
  
  /////////////////Fake reactive stream library/////////////////////////////////
  import org.reactivestreams.{ Publisher, Subscriber, Subscription }
  
  class TweetPublisher extends Publisher[Tweet] {
    def subscribe(s: Subscriber[_ >: Tweet]): Unit = {}
  }
  
  class AuthorSubscriber[_ <: Author] extends Subscriber[Author] {
    def onSubscribe(s: Subscription): Unit = {}
    def onNext(a: Author): Unit = {}
    def onError(t: Throwable): Unit = {}
    def onComplete(): Unit = {}
  }
 
}

