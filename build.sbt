name := "AkkaStreamSample"

organization := "org.gwgs"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream-experimental" % "2.0.1",
  "com.typesafe.akka" %% "akka-http-core-experimental" % "2.0.1",
  "com.typesafe.akka" %% "akka-http-experimental" % "2.0.1",
  "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "2.0.1",
  "com.typesafe.akka" %% "akka-http-testkit-experimental" % "2.0.1",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.12",
  "org.scalatest" %% "scalatest" % "2.1.6" % "compile, test",
  "junit" % "junit" % "4.11" % "test",
  "com.novocode" % "junit-interface" % "0.10" % "test"
)

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
