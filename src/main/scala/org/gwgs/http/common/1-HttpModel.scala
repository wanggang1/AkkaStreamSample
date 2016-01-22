package org.gwgs.http.common

import akka.http.scaladsl.model._
import akka.util.ByteString

import scala.util.Try

object HttpModel {
  
  def requests = {
    import HttpMethods._
    import headers.{ Authorization, BasicHttpCredentials }
 
    // construct a simple GET request to `homeUri`
    val homeUri = Uri("/abc")
    HttpRequest(GET, uri = homeUri)

    // construct simple GET request to "/index" (implicit string to Uri conversion)
    HttpRequest(GET, uri = "/index")

    // construct simple POST request containing entity
    val data = ByteString("abc")
    HttpRequest(POST, uri = "/receive", entity = data)

    // customize every detail of HTTP request
    import HttpProtocols._
    import MediaTypes._
    import HttpCharsets._
    val userData = ByteString("abc")
    val authorization = Authorization(BasicHttpCredentials("user", "pass"))
    HttpRequest(
      PUT,
      uri = "/user",
      entity = HttpEntity(`text/plain` withCharset `UTF-8`, userData),
      headers = List(authorization),
      protocol = `HTTP/1.0`)
  }
  
  def response = {
    import StatusCodes._
 
    // simple OK response without data created using the integer status code
    HttpResponse(200)

    // 404 response created using the named StatusCode constant
    HttpResponse(NotFound)

    // 404 response with a body explaining the error
    HttpResponse(404, entity = "Unfortunately, the resource couldn't be found.")

    // A redirecting response containing an extra header
    val locationHeader = headers.Location("http://example.com/other")
    HttpResponse(Found, headers = List(locationHeader))
  }
  
  def header = {
    import headers._

    // create a ``Location`` header
    val loc = Location("http://example.com/other")

    // create an ``Authorization`` header with HTTP Basic authentication data
    val auth = Authorization(BasicHttpCredentials("joe", "josepp"))

    // custom type
    case class User(name: String, pass: String)

    // a method that extracts basic HTTP credentials from a request
    def credentialsOfRequest(req: HttpRequest): Option[User] =
      for {
        Authorization(BasicHttpCredentials(user, pass)) <- req.header[Authorization]
      } yield User(user, pass)

  }
  
  def customHeader = {
    import headers._

    object ApiTokenHeader extends ModeledCustomHeaderCompanion[ApiTokenHeader] {
      override val name = "apiKey"
      override def parse(value: String) = Try(new ApiTokenHeader(value))
    }
    final class ApiTokenHeader(token: String) extends ModeledCustomHeader[ApiTokenHeader] {
      override val companion = ApiTokenHeader
      override def value: String = token
    }
    
    val ApiTokenHeader(t1) = ApiTokenHeader("token")
    assert(t1 == "token")

    val RawHeader(k2, v2) = ApiTokenHeader("token")
    assert(k2 == "apiKey")
    assert(v2 == "token")

    // will match, header keys are case insensitive
    val ApiTokenHeader(v3) = RawHeader("APIKEY", "token")
    assert(v3 == "token")

    //demo purpose only, need to put in a test
//    intercept[MatchError] {
//      // won't match, different header name
//      val ApiTokenHeader(v4) = DifferentHeader("token")
//    }
//
//    intercept[MatchError] {
//      // won't match, different header name
//      val RawHeader("something", v5) = DifferentHeader("token")
//    }
//
//    intercept[MatchError] {
//      // won't match, different header name
//      val ApiTokenHeader(v6) = RawHeader("different", "token")
//    }

  }

}
