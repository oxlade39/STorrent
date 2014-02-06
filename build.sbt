name := "storrent"

version := "1.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-slf4j" % "2.2.3",
  "commons-io" % "commons-io" % "2.1",
  "ch.qos.logback" % "logback-classic" % "1.0.7",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test",
  "org.scalatest" %% "scalatest" % "1.9.2-SNAP2" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test")

atmosSettings