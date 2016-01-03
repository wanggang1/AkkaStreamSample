package org.gwgs.http.common

/*
type FromEntityUnmarshaller[T] = Unmarshaller[HttpEntity, T]
type FromMessageUnmarshaller[T] = Unmarshaller[HttpMessage, T]
type FromResponseUnmarshaller[T] = Unmarshaller[HttpResponse, T]
type FromRequestUnmarshaller[T] = Unmarshaller[HttpRequest, T]
type FromStringUnmarshaller[T] = Unmarshaller[String, T]
type FromStrictFormFieldUnmarshaller[T] = Unmarshaller[StrictForm.Field, T]
 */

import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.util.FastFuture
import akka.stream.{ ActorMaterializer, Materializer }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

object UnMarshalling {
  
  /**
   * Creates an `Unmarshaller` from the given function.
   */
  def apply[A, B](f: ExecutionContext ⇒ A ⇒ Future[B]): Unmarshaller[A, B] =
    withMaterializer(ec => _ => f(ec))

  def withMaterializer[A, B](f: ExecutionContext ⇒ Materializer => A ⇒ Future[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] {
      def apply(a: A)(implicit ec: ExecutionContext, materializer: Materializer) =
        try f(ec)(materializer)(a)
        catch { case NonFatal(e) ⇒ FastFuture.failed(e) }
    }

  /**
   * Helper for creating a synchronous `Unmarshaller` from the given function.
   */
  def strict[A, B](f: A ⇒ B): Unmarshaller[A, B] = Unmarshaller(_ => a ⇒ FastFuture.successful(f(a)))

  /**
   * Helper for creating a "super-unmarshaller" from a sequence of "sub-unmarshallers", which are tried
   * in the given order. The first successful unmarshalling of a "sub-unmarshallers" is the one produced by the
   * "super-unmarshaller".
   */
  def firstOf[A, B](unmarshallers: Unmarshaller[A, B]*): Unmarshaller[A, B] = null //...
  
}

/*
 * reusing existing unmarshallers for the custom types. The idea is to "wrap" an
 * existing unmarshaller with some logic to "re-target" it to a custom type.
 * 
  baseUnmarshaller.transform
  baseUnmarshaller.map
  baseUnmarshaller.mapWithInput
  baseUnmarshaller.flatMap
  baseUnmarshaller.flatMapWithInput
  baseUnmarshaller.recover
  baseUnmarshaller.withDefaultValue
  baseUnmarshaller.mapWithCharset (only available for FromEntityUnmarshallers)
  baseUnmarshaller.forContentTypes (only available for FromEntityUnmarshallers)
 */


/*
 * In many places throughput Akka HTTP unmarshallers are used implicitly, e.g.
 * when you want to access the entity of a request using the Routing DSL.
 *
 * However, you can also use the unmarshalling infrastructure directly if you wish,
 * which can be useful for example in tests. The best entry point for this is the
 * akka.http.scaladsl.unmarshalling.Unmarshal object, which you can use like this:
 */
object UsingUnmarshaller {
  import akka.actor.ActorSystem
  import akka.http.scaladsl.unmarshalling.Unmarshal
  
  import scala.concurrent.Await
  import scala.concurrent.duration._

  implicit val system = ActorSystem("test")
  import system.dispatcher // ExecutionContext
  implicit val materializer: Materializer = ActorMaterializer()

  val intFuture = Unmarshal("42").to[Int]
  val int = Await.result(intFuture, 1.second) // don't block in non-test code!
  int == 42

  val boolFuture = Unmarshal("off").to[Boolean]
  val bool = Await.result(boolFuture, 1.second) // don't block in non-test code!
  bool == false
  
}
