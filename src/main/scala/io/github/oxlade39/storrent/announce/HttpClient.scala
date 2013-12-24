package io.github.oxlade39.storrent.announce

import io.github.oxlade39.storrent.logger.Logging
import scala.concurrent.{ExecutionContext, Promise}
import akka.util.{ByteStringBuilder, ByteString}
import java.io.{BufferedInputStream, ByteArrayOutputStream, ByteArrayInputStream, InputStream}
import scala.io.Source

trait HttpClient {

  import scala.concurrent.Future

  def getBytesFrom(url: String): Future[ByteString]

  def shutdown(): Unit = {}

  case class BadResponse(code: Int, text: String) extends RuntimeException

}

class RawHttpClientComponent(implicit executionContext: ExecutionContext) extends HttpClient with Logging {
  import scala.concurrent._
  import java.net.URL

  def getBytesFrom(url: String): Future[ByteString] = Future {
    val stream: InputStream = new URL(url).openStream()
    val bytes = try {
      Stream.continually(stream.read).takeWhile(-1 !=).map(_.toByte).toArray
    } finally {
      stream.close()
    }
    ByteString(bytes)
  }
}