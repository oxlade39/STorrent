package io.github.oxlade39.storrent.announce

import org.scalatest.{WordSpecLike, BeforeAndAfterAll}
import akka.testkit.{TestKit, ImplicitSender}
import akka.actor._
import akka.util.ByteString
import scala.concurrent.Future
import java.net.URL
import io.github.oxlade39.storrent.test.util.StepParent
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import io.github.oxlade39.storrent.core.{BInt, Torrent, BBytes, BMap}
import org.scalatest.matchers.MustMatchers


class HttpTrackerClientTest extends TestKit(ActorSystem("HttpTrackerClientTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MockitoSugar with MustMatchers {
  import HttpTrackerClientTest._

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "HttpTrackerClient" must {
    "stop after tracker responds with 404" in {
      val r = mock[TrackerRequest]
      val url = new URL("http://notfound.com")
      when(r.appendParams(url)).thenReturn(new URL("http://notfound.com/torrent?with=params"))
      val trackerClient = system.actorOf(Props(new StepParent(fakeHttpTrackerClient(url, r), testActor)), "wrongLink")
      watch(trackerClient)
      expectTerminated(trackerClient)
    }

    "propagate tracker FailureResponse" in {
      val r = mock[TrackerRequest]
      val url = new URL("http://found.com")
      when(r.appendParams(url)).thenReturn(new URL("http://found.com/torrent?with=params"))
      val trackerClient = system.actorOf(Props(new StepParent(
        fakeHttpTrackerClient(url, r), testActor)), "failureResponse")
      watch(trackerClient)

      expectMsg(FailureTrackerResponse("I Failed"))
      expectTerminated(trackerClient)
    }

    "reply with SuccessResponse" in {
      val r = mock[TrackerRequest]
      val url = new URL("http://nicetracker.com")
      when(r.appendParams(url)).thenReturn(new URL("http://nicetracker.com/torrent?with=params"))
      val trackerClient = system.actorOf(Props(new StepParent(
        fakeHttpTrackerClient(url, r), testActor)), "successResponse")
      watch(trackerClient)

      val response = expectMsgType[NormalTrackerResponse]
      response.peers.size mustEqual 1
      expectTerminated(trackerClient)
    }
  }
}

object HttpTrackerClientTest {

  def fakeHttpTrackerClient(uri: URL, request: TrackerRequest): Props =
    Props(new HttpTrackerClient(uri, request) {
      override def httpClient = FakeHttpClient
    })

  object FakeHttpClient extends HttpClient {
    val responses = Map(
      "http://tracker.torrent.com/torrent1" -> ByteString(""),
      "http://found.com/torrent?with=params" -> BMap(Map(
        BBytes("failure reason") -> BBytes(ByteString("I Failed", Torrent.encoding))
      )).encode,
      "http://nicetracker.com/torrent?with=params" -> BMap(Map(
        BBytes("peers") -> BBytes(ByteString(Seq(192,169,0,1,8080,0).map(_.toByte).toArray)),
        BBytes("interval") -> BInt(10),
        BBytes("complete") -> BInt(0),
        BBytes("incomplete") -> BInt(100001)
      )).encode
    )

    def getBytesFrom(url: String) = responses.get(url) match {
      case Some(b) => Future.successful(b)
      case _ => Future.failed(BadResponse(404, "Not Found"))
    }
  }
}

