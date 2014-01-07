package io.github.oxlade39.storrent.peer

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor._
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.{ForwardingParent, FileOps}
import akka.io.{Tcp, IO}
import java.net.InetSocketAddress
import io.github.oxlade39.storrent.core.Torrent
import concurrent.duration._

/**
 * @author dan
 */
class PeerConnectionTest extends TestKit(ActorSystem("PeerConnectionTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  import PeerConnectionTest._
  import io.github.oxlade39.storrent.piece.PieceManager._

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "PeerConnection" must {
    "attempt to handshake with peer" in {

      val fakePeer = new FakePeer

      val peer: Peer = new Peer(fakePeer.peerAddress)
      val peerConnection = system.actorOf(PeerConnection.props(
        peer,
        Torrent("examples" / "ubuntu.torrent"),
        TestProbe().ref),
        "under-test")

      fakePeer.connectsWithPeer(peer)
      val hs = fakePeer.receivesHandshake()
      val myPeerId: PeerId = PeerId()
      fakePeer.sendsHandshake(hs.copy(peerId = myPeerId))

    }

    "notifies piece manager with bitfield of new peer" in {
      val fakePeer = new FakePeer

      val fakePieceManager = TestProbe()

      val peer: Peer = new Peer(fakePeer.peerAddress)
      val torrent: Torrent = Torrent("examples" / "ubuntu.torrent")
      val peerConnection = system.actorOf(PeerConnection.props(peer, torrent, fakePieceManager.ref))

      fakePeer.connectsWithPeer(peer)
      val hs = fakePeer.receivesHandshake()
      val myPeerId: PeerId = PeerId()
      fakePeer.sendsHandshake(hs.copy(peerId = myPeerId))
      val allPieces = Bitfield.padded(torrent.pieceHashes.map(_ => true))
      fakePeer.sends(allPieces) // the fakePeer has all the pieces

      fakePieceManager.expectMsg(PeerHasPieces(peer.id, allPieces))
    }
  }

}

object PeerConnectionTest {

  def fakePeerProps(remoteControl: ActorRef)= Props(new FakePeerActor(remoteControl))

  class FakePeer(implicit val system: ActorSystem) {
    val remoteControl = TestProbe()
    val actor = system.actorOf(Props(new ForwardingParent(fakePeerProps(remoteControl.ref), remoteControl.ref)))

    val peerAddress = remoteControl.expectMsgType[Tcp.Bound].localAddress

    def sends(m: Message) = {
      remoteControl.send(actor, m)
    }

    def sendsHandshake(hs: Handshake) = {
      remoteControl.send(actor, hs)
    }

    def connectsWithPeer(p: Peer) = {
      remoteControl.expectMsgType[Tcp.Connected]
    }

    def receivesHandshake(): Handshake = {
      val received = remoteControl.expectMsgType[Tcp.Received]
      val handshake: Option[Handshake] = Handshake.parse(received.data)
      handshake.getOrElse(throw new java.lang.AssertionError(s"Couldn't parse message [${received.data.utf8String}}] into handshake"))
    }
  }

  class FakePeerActor(remoteControl: ActorRef) extends Actor with ActorLogging {

    import context._

    IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(0))

    var connection = Option.empty[ActorRef]

    def receive = {
      case bound: Tcp.Bound => parent ! bound
      case msg: Message if sender == remoteControl =>
        connection foreach (_ ! Tcp.Write(msg.encode))
      case hs: Handshake if sender == remoteControl =>
        connection foreach (_ ! Tcp.Write(hs.encoded))

      case Tcp.Closed => stop(self)
      case Tcp.Close => stop(self)

      case c: Tcp.Connected =>
        connection = Some(sender)
        sender ! Tcp.Register(self)
        remoteControl forward c

      case other => remoteControl forward other
    }
  }

}