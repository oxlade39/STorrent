package io.github.oxlade39.storrent.announce

import org.scalatest.{WordSpecLike, BeforeAndAfterAll}
import akka.testkit.{TestKit, ImplicitSender}
import akka.actor._
import java.net.URL
import io.github.oxlade39.storrent.test.util.{FileOps, StepParent}
import io.github.oxlade39.storrent.core.Torrent
import io.github.oxlade39.storrent.peer.PeerId
import org.scalatest.matchers.MustMatchers
import akka.util.ByteString
import scala.concurrent.duration._


class HttpTrackerClientIntegrationTest extends TestKit(ActorSystem("HttpTrackerClientIntegrationTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "HttpTrackerClient" must {
    "connect to a real tracker" in {
      val t = Torrent.fromFile("examples" / "ubuntu.torrent")
      val announceUrl = t.announceList.head.head.toURL
      val request = TrackerRequest(
        t.infoHash,
        PeerId(ByteString("-TO0042-60ca4661be1b")),
        port = 6881,
        uploaded = 0,
        downloaded = 0,
        left = t.getSize,
        acceptCompact = true,
        noPeerId = false,
        event = Some(Started)
      )

      request.appendParams(announceUrl) mustEqual new URL("http://torrent.ubuntu.com:6969/announce?info_hash=%08%8C%2FM_%3EP%1A8%7D%81%80%16%B8%B9%C6%2F%CD%B0%21&peer_id=-TO0042-60ca4661be1b&port=6881&uploaded=0&downloaded=0&left=714567680&compact=1&no_peer_id=0&event=started")

      val trackerClientProps = Props(new StepParent(HttpTrackerClient.props(announceUrl, request), testActor))
      val trackerClient = watch(system.actorOf(trackerClientProps, "tracker-client-integration-test"))
      val response = expectMsgType[TrackerResponse](10.minutes)
      response.isInstanceOf[NormalTrackerResponse] mustEqual true
      expectTerminated(trackerClient)
    }
  }
}



