package org.gwgs.http.common

/*
 * Below are basic designs in akka http library
 */

/*

  import scala.collection.immutable.{ Seq => ImmutableSeq }
  
  type ToEntityMarshaller[T] = Marshaller[T, MessageEntity]
  type ToByteStringMarshaller[T] = Marshaller[T, ByteString]
  type ToHeadersAndEntityMarshaller[T] = Marshaller[T, (ImmutableSeq[HttpHeader], MessageEntity)]
  type ToResponseMarshaller[T] = Marshaller[T, HttpResponse]
  type ToRequestMarshaller[T] = Marshaller[T, HttpRequest]

  Marshaller[A, B] is similar to function A => Future[List[Marshalling[B]]]
 */

/*
//Describes one possible option for marshalling a given value.
sealed trait Marshalling[+A] {
  def map[B](f: A ⇒ B): Marshalling[B]

  //Converts this marshalling to an opaque marshalling, i.e. a marshalling result that
  //does not take part in content type negotiation. The given charset is used if this
  //instance is a `WithOpenCharset` marshalling.
  def toOpaque(charset: HttpCharset): Marshalling[A]
}

object Marshalling {
  
  //A Marshalling to a specific [[ContentType]].
  final case class WithFixedContentType[A](
    contentType: ContentType,
    marshal: () ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): WithFixedContentType[B] = copy(marshal = () ⇒ f(marshal()))

    def toOpaque(charset: HttpCharset): Marshalling[A] = Opaque(marshal)
  }

  //A Marshalling to a specific [[akka.http.scaladsl.model.MediaType]] with a flexible charset.
  final case class WithOpenCharset[A](mediaType: MediaType.WithOpenCharset,
                                      marshal: HttpCharset ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): WithOpenCharset[B] = copy(marshal = cs ⇒ f(marshal(cs)))
    def toOpaque(charset: HttpCharset): Marshalling[A] = Opaque(() ⇒ marshal(charset))
  }

  //A Marshalling to an unknown MediaType and charset. Circumvents content negotiation.
  final case class Opaque[A](marshal: () ⇒ A) extends Marshalling[A] {
    def map[B](f: A ⇒ B): Opaque[B] = copy(marshal = () ⇒ f(marshal()))
    def toOpaque(charset: HttpCharset): Marshalling[A] = this
  }
}
*/

/*
 * In many places throughput Akka HTTP marshallers are used implicitly, e.g. when
 * you define how to complete a request using the Routing DSL.
 *
 * However, you can also use the marshalling infrastructure directly if you wish,
 * which can be useful for example in tests. The best entry point for this is the
 * akka.http.scaladsl.marshalling.Marshal object, which you can use like this:
 */
object UsingMashalers {
  
  import scala.concurrent.Await
  import scala.concurrent.duration._
  import akka.http.scaladsl.marshalling.Marshal
  import akka.http.scaladsl.model._

  import scala.concurrent.ExecutionContext.Implicits.global

  val string = "Yeah"
  val entityFuture = Marshal(string).to[MessageEntity]
  val entity = Await.result(entityFuture, 1.second) // don't block in non-test code!
  entity.contentType == ContentTypes.`text/plain(UTF-8)`

  val errorMsg = "Easy, pal!"
  val responseFuture = Marshal(420 -> errorMsg).to[HttpResponse]
  val response = Await.result(responseFuture, 1.second) // don't block in non-test code!
  response.status == StatusCodes.EnhanceYourCalm
  response.entity.contentType == ContentTypes.`text/plain(UTF-8)`

  val request = HttpRequest(headers = List(headers.Accept(MediaTypes.`application/json`)))
  val responseText = "Plaintext"
  val respFuture = Marshal(responseText).toResponseFor(request) // with content negotiation!
  /*
  a[Marshal.UnacceptableResponseContentTypeException] should be thrownBy {
    Await.result(respFuture, 1.second) // client requested JSON, we only have text/plain!
  }
  */
}