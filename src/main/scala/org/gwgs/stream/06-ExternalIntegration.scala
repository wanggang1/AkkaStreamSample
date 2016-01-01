package org.gwgs

import akka.actor.{ Actor, ActorSystem, Props }
import akka.stream.{ ActorAttributes, ActorMaterializer , ActorMaterializerSettings}
import akka.stream.scaladsl._
import akka.util.Timeout

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object ExternalIntegration {

  import scala.concurrent.ExecutionContext.Implicits.global
  
  /*
   * Stream transformations and side effects involving external non-stream based
   * services can be performed with mapAsync or mapAsyncUnordered.
   * 
   * For example, external email service
   */
  case class Email(to: String = "", title: String = "", body: String = "")
  case class TextMessage(to: Int, body: String)
  
  def send(email: Email): Future[Unit] = Future {
    // sending...
    Thread.sleep(100)
    println(s"${email.to} ${email.body}")
  }
  
  def sendSMS(text: TextMessage): Unit = {
    // sending...
    Thread.sleep(100)
    println(s"${text.to} ${text.body}")
  }
  
  def lookupEmail(handle: String): Future[Option[String]] = Future {
    val parts = handle.split(" ")
    Some(parts.last)
  }
  
  /*
   * mapAsync is applying the given function that is calling out to the external
   * service to each of the elements as they pass through this processing step.
   * The external function returns a Future and the VALUE of that future will be
   * emitted downstreams.  These Futures may complete in any order, but the elements
   * that are emitted downstream are in the SAME ORDER as received from upstream.
   * 
   * with mapAsync(4), order seems not kept in this method????, but ordered() below shows
   * the order is kept!!!!!
   * 
   * In mapAsync(4)(func), The argument "4" the numner of Futures that shall run
   * in parallel. 
   */
  def externalOrdered(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import QuickStart.{ akkaTag, tweets }

    val authors: Source[Author, Unit] = tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    val emailAddresses: Source[String, Unit] =
      authors.mapAsync(4)(author => lookupEmail(author.handle))
        .collect { case Some(emailAddress) => emailAddress }
    
    val sendEmails: RunnableGraph[Unit] =
      emailAddresses
        .mapAsync(4)(address => {
          send(Email(to = address, title = "Akka", body = "I like your tweet"))
        })
        .to(Sink.ignore)
 
    sendEmails.run()
  }
  
  /*
   * mapAsync preserves the order of the stream elements. In the case order is not important,
   * the more efficient mapAsyncUnordered can be used
   */
  def externalUnOrdered(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import QuickStart.{ akkaTag, tweets }

    val authors: Source[Author, Unit] = tweets.filter(_.hashtags.contains(akkaTag)).map(_.author)

    val emailAddresses: Source[String, Unit] =
      authors
        .mapAsyncUnordered(4)(author => lookupEmail(author.handle))
        .collect { case Some(emailAddress) => emailAddress }

    val sendEmails: RunnableGraph[Unit] =
      emailAddresses
        .mapAsyncUnordered(4)(address => {
          send( Email(to = address, title = "Akka", body = "I like your tweet") )
        })
        .to(Sink.ignore)

    sendEmails.run()
  }
  
  /*
   * If external call is not in a future, wrap the call in a Future. If the service
   * call involves blocking , run on a dedicated execution context to avoid starvation
   * and disturbance of other tasks in the system.
   */
  def externalBlocking(implicit system: ActorSystem, materializer: ActorMaterializer) = {

    val blockingExecutionContext = system.dispatchers.lookup("stream.my-blocking-dispatcher")
 
    val sendTextMessages: RunnableGraph[Unit] =
      Source(1 to 10)
        .mapAsync(4)(phoneNo => {
          Future {
            sendSMS( TextMessage(to = phoneNo, body = "I like your text") )
          }(blockingExecutionContext)
        })
        .to(Sink.ignore)

    sendTextMessages.run()
    
    //An alternative for blocking calls is to perform them in a map operation,
    //still using a dedicated dispatcher for that operation.
    //
    //that is not exactly the same as mapAsync, since the mapAsync may run several
    //calls concurrently, but map performs them one at a time.
    //
    //BUT, This guarentees ORDER!!!!!!
    val send = Flow[Int].map{ phoneNo => 
        sendSMS(TextMessage(to = phoneNo, body = "I like your text with map"))
      }.withAttributes(ActorAttributes.dispatcher("stream.my-blocking-dispatcher"))
          
    val sendTextMessages2: RunnableGraph[Unit] = Source(1 to 10).via(send).to(Sink.ignore)

    sendTextMessages2.run()
  }
  
  /*
   * For a service that is exposed as an actor, or if an actor is used as a gateway
   * in front of an external service, you can use ask
   */
  def externalActor(implicit system: ActorSystem, materializer: ActorMaterializer) = {
    import QuickStart.{ akkaTag, tweets }
    import TweetDB._
    import akka.pattern.ask
    
    import scala.concurrent.duration._
    implicit val timeout = Timeout(3.seconds)
    
    val akkaTweets: Source[Tweet, Unit] = tweets.filter(_.hashtags.contains(akkaTag))
 
    val database = system.actorOf(TweetDB.props)
    
    val saveTweets: RunnableGraph[Unit] =
      akkaTweets
        .mapAsync(4)(tweet => database ? Save(tweet))
        .to(Sink.ignore)

    saveTweets.run()
  }

  /*
   * mapAsync keeps the order, after's order is the same as before's order
   */
  def ordered(implicit system: ActorSystem) = {
    implicit val blockingExecutionContext = system.dispatchers.lookup("stream.my-blocking-dispatcher")
    
    val service = new SometimesSlowService

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem => { println(s"before: $elem"); elem })
      .mapAsync(4)(service.convert)
      .runForeach(elem => println(s"after: $elem"))
  
  }

  /*
   * mapAsyncUnordered emits the future results as soon as they are completed,
   * therefore after's order is NOT the same as before's order
   */
  def unOrdered(implicit system: ActorSystem) = {
    implicit val blockingExecutionContext = system.dispatchers.lookup("stream.my-blocking-dispatcher")
    
    val service = new SometimesSlowService

    implicit val materializer = ActorMaterializer(
      ActorMaterializerSettings(system).withInputBuffer(initialSize = 4, maxSize = 4))

    Source(List("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
      .map(elem => { println(s"before: $elem"); elem })
      .mapAsyncUnordered(4)(service.convert)
      .runForeach(elem => println(s"after: $elem"))
  
  }

  //////////////////////////////////////////////////////////////////////////////
  object TweetDB {
    case class Save(tweet: Tweet)
    
    def props: Props = Props[TweetDB]
  }
  
  class TweetDB extends Actor {
    import TweetDB._
    
    def receive = {
      case Save(tweet) => 
        println(s"$tweet is saved")
        sender ! ()
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////
  class SometimesSlowService(implicit ec: ExecutionContext) {
 
    private val runningCount = new AtomicInteger

    def convert(s: String): Future[String] = {
      println(s"running: $s (${runningCount.incrementAndGet()})")
      Future {
        if (s.nonEmpty && s.head.isLower)
          Thread.sleep(500)
        else
          Thread.sleep(20)
        println(s"completed: $s (${runningCount.decrementAndGet()})")
        s.toUpperCase
      }
    }
  }

}

