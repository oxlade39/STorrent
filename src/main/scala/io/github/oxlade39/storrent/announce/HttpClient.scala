package io.github.oxlade39.storrent.announce

import io.github.oxlade39.storrent.logger.Logging
import scala.concurrent.Promise
import akka.util.ByteString

trait HttpClient {

  import scala.concurrent.Future

  def getBytesFrom(url: String): Future[ByteString]

  def shutdown(): Unit = {}

  case class BadResponse(code: Int, text: String) extends RuntimeException

}

object RealHttpClientComponent extends HttpClient with Logging {
  import com.ning.http.client._

  val asyncClient = new AsyncHttpClient()

  def getBytesFrom(url: String) = {
    val result = Promise[ByteString]()
    asyncClient.prepareGet(url).execute(new AsyncCompletionHandlerBase {
      override def onCompleted(response: Response) = {
        val code = response.getStatusCode
        if (code != 200) {
          logger.error("bad status code {} for {}", code, url)
          result.failure(BadResponse(code, response.getStatusText))
        } else {
          result.success(ByteString(response.getResponseBodyAsByteBuffer))
        }
        super.onCompleted(response)
      }
    })
    result.future
  }

  override def shutdown() = asyncClient.closeAsynchronously()

}