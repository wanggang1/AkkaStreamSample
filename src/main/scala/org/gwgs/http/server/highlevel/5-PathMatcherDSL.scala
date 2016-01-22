package org.gwgs.http.server.highlevel

import akka.http.scaladsl.server.PathMatcher1
import akka.http.scaladsl.server.Directives._


/**
  * Created by gang.wang on 1/22/16.
  */
object PathMatcherDSL {

  /*
   * This will match paths like foo/bar/X42/edit or foo/bar/X/create.
   */
  val matcher: PathMatcher1[Option[Int]] =
    "foo" / "bar" / "X" ~ IntNumber.? / ("edit" | "create")


  // matches /foo/
  path("foo"./)

  // matches e.g. /foo/123 and extracts "123" as a String
  path("foo" / """\d+""".r)

  // matches e.g. /foo/bar123 and extracts "123" as a String
  path("foo" / """bar(\d+)""".r)

  // similar to `path(Segments)`
  path(Segment.repeat(10, separator = Slash))

  // matches e.g. /i42 or /hCAFE and extracts an Int
  path("i" ~ IntNumber | "h" ~ HexIntNumber)

  // identical to path("foo" ~ (PathEnd | Slash))
  path("foo" ~ Slash.?)

  // matches /red or /green or /blue and extracts 1, 2 or 3 respectively
  path(Map("red" -> 1, "green" -> 2, "blue" -> 3))

  // matches anything starting with "/foo" except for /foobar
  pathPrefix("foo" ~ !"bar")

}
