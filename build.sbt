name := "AkkaStreamSample"

organization := "org.gwgs"

version := "1.0"

scalaVersion := "2.11.11"

libraryDependencies ++= {
    val akkaVersion = "2.5.2"
    val akkaHttpVersion = "10.0.7"
    val akkaStreamVersion = "2.5.2"

    Seq(
      "com.typesafe.akka" %% "akka-stream" % akkaStreamVersion,
      "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaStreamVersion,
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
      "org.scalatest" %% "scalatest" % "2.1.6" % "compile, test",
      "junit" % "junit" % "4.11" % "test",
      "com.novocode" % "junit-interface" % "0.10" % "test"
    )
}

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v")

resolvers += Classpaths.sbtPluginReleases

instrumentSettings

ScoverageKeys.minimumCoverage := 70

ScoverageKeys.failOnMinimumCoverage := false

ScoverageKeys.highlighting := {
  if (scalaBinaryVersion.value == "2.10") true
  else true
}

fork in run := true

publishArtifact in Test := false

parallelExecution in Test := false
