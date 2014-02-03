package io.github.oxlade39.storrent.announce

import org.scalatest.{WordSpecLike, BeforeAndAfterAll}
import akka.actor._
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.MustMatchers
import java.net.{InetSocketAddress, URI}
import io.github.oxlade39.storrent.core.Torrent
import org.mockito.Mockito._
import io.github.oxlade39.storrent.peer.{Discovered, PeerId, Peer}
import io.github.oxlade39.storrent.test.util.{ForwardingParent, StepParent}
import concurrent.duration._

class TrackerTest extends TestKit(ActorSystem("TrackerTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MockitoSugar with MustMatchers  {
  import TrackerTest._

  "Tracker" must {
    "send initial messages to the first tier" in {
      val torrent = mock[Torrent]
      val request = mock[TrackerRequest]
      when(torrent.announceList).thenReturn(tiers)
      val httpTracker = TestProbe()

      val children = tiers(0).map(uri => (uri, httpTracker.ref)).toMap
      val underTest = system.actorOf(Props(new ForwardingParent(fakeTracker(torrent, children, request), self)))

      httpTracker.expectMsgType[TrackerRequest]
      httpTracker.send(underTest, normalResponse)

      expectMsg(Discovered(List(peer)))
    }

    "move to second tier once first is exhausted" in {
      val torrent = mock[Torrent]
      val request = mock[TrackerRequest]
      when(torrent.announceList).thenReturn(tiers)
      val httpTracker, udpTracker = TestProbe()

      val children =
        tiers(0).map(uri => (uri, httpTracker.ref)).toMap ++
          tiers(1).map(uri => (uri, udpTracker.ref))

      val underTest = system.actorOf(Props(new StepParent(fakeTracker(torrent, children, request), self)))

      for (tier <- tiers(0)) {
        httpTracker.expectMsgType[TrackerRequest]
        httpTracker.reply(FailureTrackerResponse("bad request"))
      }
      udpTracker.expectMsgType[TrackerRequest]
      udpTracker.reply(normalResponse)

      expectMsg(Discovered(List(peer)))
    }

    "schedule successive requests for the given time in a successful response" in {
      val torrent = mock[Torrent]
      val request = mock[TrackerRequest]
      when(torrent.announceList).thenReturn(tiers)
      val httpTracker = TestProbe()

      val clientRequestInterval = 1.second
      val children =
        tiers(0).map(uri => (uri, httpTracker.ref)).toMap

      val underTest = system.actorOf(Props(new StepParent(fakeTracker(torrent, children, request), self)))

      httpTracker.expectMsgType[TrackerRequest]
      httpTracker.reply(NormalTrackerResponse(
        clientRequestInterval = clientRequestInterval,
        numberOfCompletedPeers = 100,
        numberOfUncompletedPeers = 100,
        peers = Nil
      ))
      expectMsg(Discovered(Nil))


      httpTracker.expectMsgType[TrackerRequest]
      httpTracker.reply(NormalTrackerResponse(
        clientRequestInterval = clientRequestInterval,
        numberOfCompletedPeers = 100,
        numberOfUncompletedPeers = 100,
        peers = List(peer)
      ))

      expectMsg(Discovered(List(peer)))
    }
  }

}

object TrackerTest {
  val tiers: List[List[URI]] = List(
    List(new URI("http://tracker1.com"), new URI("http://tracker2.com"), new URI("http://tracker3.com")),
    List(new URI("udp://tracker4.com"), new URI("udp://tracker5.com"), new URI("http://tracker6.com")),
    List(new URI("https://tracker7.com"), new URI("https://tracker8.com"), new URI("https://tracker9.com"))
  )

  def fakeTracker(t: Torrent, children: Map[URI, ActorRef], request: TrackerRequest) = Props(new Tracker(t) {
    override def clientFor(uri: URI): Props =
      Props(new Actor {

        children.get(uri).foreach(_ ! request)

        def receive: Receive =
        {
          case m: NormalTrackerResponse =>
            context.parent forward m
            context.stop(self)

          case m =>
            context.parent forward m
        }
      })
  })

  def peer = Peer(new InetSocketAddress("peer.com", 8080), PeerId("peer.com"))

  def normalResponse = NormalTrackerResponse(
    clientRequestInterval = 0.seconds,
    numberOfCompletedPeers = 1,
    numberOfUncompletedPeers = 99,
    peers = List(peer)
  )

}