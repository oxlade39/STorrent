name := "storrent"

version := "1.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3-M1",
  "com.typesafe.akka" %% "akka-testkit" % "2.3-M1",
  "com.typesafe.akka" %% "akka-cluster" % "2.3-M1",
  "com.ning" % "async-http-client" % "1.7.19",
  "ch.qos.logback" % "logback-classic" % "1.0.7",
  "org.scalatest" %% "scalatest" % "1.9.2-SNAP2" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test")