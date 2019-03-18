package io.github.oxlade39.storrent.peer

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.MustMatchers
import io.github.oxlade39.storrent.test.util.{ForwardingParent, Files, FileOps}
import java.net.InetSocketAddress
import io.github.oxlade39.storrent.piece.PieceManager

/**
 * @author dan
 */
class DownloaderTest extends TestKit(ActorSystem("DownloaderTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  import Downloader._
  import Files._
  import concurrent.duration._

  override def afterAll(): Unit = {
    system.terminate()
  }

  "Downloader" must {
    "requests connected peers from parent PeerManager" in {
      val pieceManager, peerManager = TestProbe()

      def underTest = props(ubuntuTorrent, pieceManager.ref, 100.millis)

      system.actorOf(Props(new ForwardingParent(underTest, peerManager.ref)), "testDownloader")

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
    }

    "when PeerManager responds with a Peer the peer is queried for its status" in {
      val pieceManager, peerManager, connectedPeer = TestProbe()

      def underTest = props(ubuntuTorrent, pieceManager.ref, 100.millis)

      system.actorOf(Props(new ForwardingParent(underTest, peerManager.ref)), "peerStatusQuery")

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
      peerManager.reply(PeerManager.ConnectedPeers(Map(connectedPeer.ref -> Peer(new InetSocketAddress(0)))))

      connectedPeer.expectMsg(PeerProtocol.GetPeerStatus)
    }

    "when Peer responds with status of local choked nothing happens" in {
      val pieceManager, peerManager, connectedPeer = TestProbe()

      def underTest = props(ubuntuTorrent, pieceManager.ref, 100.millis)

      system.actorOf(Props(new ForwardingParent(underTest, peerManager.ref)), "peerChoked")

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
      peerManager.reply(PeerManager.ConnectedPeers(Map(connectedPeer.ref -> Peer(new InetSocketAddress(0)))))
      connectedPeer.expectMsg(PeerProtocol.GetPeerStatus)
      val status = (
        PeerProtocol.PeerStatus(choked = true, interested = true, pieces = Bitfield(Seq())), // local
        PeerProtocol.PeerStatus(choked = true, interested = false, pieces = Bitfield(Seq()))
      )
      connectedPeer.reply(status)

      connectedPeer.expectNoMsg()
    }

    "when Peer responds with status of local unchoked and local interested then piece manager is queried" in {
      val pieceManager, peerManager, connectedPeer = TestProbe()

      def underTest = props(ubuntuTorrent, pieceManager.ref, 100.millis)

      system.actorOf(Props(new ForwardingParent(underTest, peerManager.ref)), "peerUnchoked")

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
      peerManager.reply(PeerManager.ConnectedPeers(Map(connectedPeer.ref -> Peer(new InetSocketAddress(0)))))
      connectedPeer.expectMsg(PeerProtocol.GetPeerStatus)
      val status = (
        PeerProtocol.PeerStatus(choked = false, interested = true, pieces = Bitfield(Seq())), // local
        PeerProtocol.PeerStatus(choked = true, interested = false, pieces = Bitfield(Seq()))
      )
      connectedPeer.reply(status)
      pieceManager.expectMsg(PieceManager.GetPeerPieceMappings)
    }

    "when PieceManager responds that this peer has pieces we want then requests are made" in {
      val pieceManager, peerManager, connectedPeer = TestProbe()
      val peerId = PeerId()

      def underTest = props(ubuntuTorrent, pieceManager.ref, 100.millis)

      system.actorOf(Props(new ForwardingParent(underTest, peerManager.ref)), "pieceManagerResponse")

      peerManager.expectMsg(PeerManager.GetConnectedPeers)
      peerManager.reply(PeerManager.ConnectedPeers(Map(connectedPeer.ref -> Peer(new InetSocketAddress(0), peerId))))
      connectedPeer.expectMsg(PeerProtocol.GetPeerStatus)
      val status = (
        PeerProtocol.PeerStatus(choked = false, interested = true, pieces = Bitfield(Seq())), // local
        PeerProtocol.PeerStatus(choked = true, interested = false, pieces = Bitfield(Seq()))
      )
      connectedPeer.reply(status)
      pieceManager.expectMsg(PieceManager.GetPeerPieceMappings)

      val pieces = PieceManager.Pieces(ubuntuTorrent.pieceCount)
      val mappings = PieceManager.PeerPieceMappings(pieces, pieces) ++
        (peerId, pieces.copy(has = Set(0, 1, 2)))

      pieceManager.reply(mappings)
      val request = connectedPeer.expectMsgPF(hint = "Request for a piece") {
        case PeerConnection.Send(r: Request) => r
      }
      Set(0, 1, 2).contains(request.index) mustEqual true
    }
  }

}
