package io.github.oxlade39.storrent.announce

import akka.actor.{Props, ReceiveTimeout, ActorLogging, Actor}
import java.net._
import akka.util.ByteString
import scala.Some
import akka.event.LoggingReceive

object HttpTrackerClient {
  def props(url: URL, request: TrackerRequest) = Props(new HttpTrackerClient(url, request))
}

class HttpTrackerClient(url: URL, request: TrackerRequest)
  extends Actor with ActorLogging {

  import akka.pattern._
  import context._
  import concurrent.duration._

  setReceiveTimeout(1.minute)
  makeRequest()

  def makeRequest() {
    val urlWithParams = request.appendParams(url).toString
    log.debug("requesting {}", urlWithParams)
    val response = httpClient getBytesFrom urlWithParams
    response map parseResponse pipeTo self
  }

  def httpClient: HttpClient = RealHttpClientComponent

  def parseResponse: ByteString => Option[TrackerResponse] = bs =>
    NormalTrackerResponse.parse(bs).orElse(FailureTrackerResponse.parse(bs))

  def receive = LoggingReceive {
    case Some(response: NormalTrackerResponse) => stopAfter {
      parent ! response
    }

    case Some(response: FailureTrackerResponse) => stopAfter {
      log.info("tracker responded with failure: {}", response)
      parent ! response
    }

    case None => stopAfter {
      log.warning("couldn't parse tracking response")
    }

    case akka.actor.Status.Failure(t) => stopAfter {
      log.error(t, "failure response: {}", t.getMessage)
    }

    case ReceiveTimeout => stopAfter {
      log.warning("no response within timeout")
    }
  }
  
  def stopAfter(f: => Unit) = {
    f
    stop(self)
  }
}
