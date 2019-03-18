lazy val akkaVersion = "2.5.21"

ThisBuild / scalaVersion := "2.12.7"
ThisBuild / organization := "io.github.oxlade39"

lazy val storrent = (project in file("."))
//  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "storrent",
    version := "2.0",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "commons-io" % "commons-io" % "2.1",
      "ch.qos.logback" % "logback-classic" % "1.0.7",
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.21" % Test,
      "org.scalatest" %% "scalatest" % "3.0.5" % Test,
      "org.mockito" % "mockito-core" % "1.9.5" % Test
    )
  )