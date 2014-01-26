package io.github.oxlade39.storrent.piece

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.actor.{Actor, Props, ActorSystem}
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.peer._
import io.github.oxlade39.storrent.test.util.Files._

class PieceManagerTest extends TestKit(ActorSystem("PeerConnectionTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers {
  import io.github.oxlade39.storrent.test.util.Files._
  import PieceManager._
  import PieceManagerTest._

  "PieceManager" should {
    "By default there are no pieces" in {
      val pieceManager = system.actorOf(pieceManagerProps)
      pieceManager ! GetPeerPieceMappings

      val mappings = expectMsgType[PeerPieceMappings]
      mappings.pieceCounts mustEqual Map.empty[Int, List[PeerId]]
      mappings.global mustEqual Pieces(ubuntuTorrent.pieceCount)
    }

    "keeps track of which peers have which pieces, telling peer we are interested if it's a piece we do not have" in {
      val peerOne, peerTwo = PeerId()
      val peerOneConnection, peerTwoConnection, downloader = TestProbe()
      val pieceManager = system.actorOf(pieceManagerProps)

      peerOneConnection.send(pieceManager,
        PeerHasPieces(peerOne, Bitfield(ubuntuTorrent.pieceHashes.map(_ => false)).set(2)))

      peerTwoConnection.send(pieceManager,
        PeerHasPieces(peerTwo, Bitfield(ubuntuTorrent.pieceHashes.map(_ => false)).set(23).set(2)))

      downloader.send(pieceManager, GetPeerPieceMappings)

      peerOneConnection.expectMsg(PeerConnection.Send(Interested))
      peerTwoConnection.expectMsg(PeerConnection.Send(Interested))
      peerTwoConnection.expectNoMsg()

      val mappings = downloader.expectMsgType[PeerPieceMappings]

      mappings.pieceCounts mustEqual Map(
        2 -> Set(peerOne, peerTwo),
        23 -> Set(peerTwo)
      )
      mappings.global mustEqual Pieces(ubuntuTorrent.pieceCount, Set(2, 23))
    }
  }
}

object PieceManagerTest {
  def pieceManagerProps = PieceManager.props(ubuntuTorrent)(Props(new Actor {
    def receive = {
      case any => context.parent forward any
    }
  }))
}

class PeerPieceMappingsTest extends WordSpecLike with MustMatchers {
  import PieceManager._

  "PeerPieceMappings" must {
    "append" in {
      val mappings =
        PeerPieceMappings(Pieces(10), Pieces(10)) ++
          (PeerId("has 0"), Pieces(10, Set(0))) ++
          (PeerId("has 5"), Pieces(10, Set(5))) ++
          (PeerId("has 9 and 0"), Pieces(10, Set(9, 0)))

      mappings mustEqual PeerPieceMappings(
        Pieces(10, Set(0,5,9)),
        Pieces(10),
        Map(
          0 -> Set(PeerId("has 0"), PeerId("has 9 and 0")),
          5 -> Set(PeerId("has 5")),
          9 -> Set(PeerId("has 9 and 0"))
        )
      )
    }

    "provide rarest pieces" in {
      val mappings =
        PeerPieceMappings(Pieces(10), Pieces(10)) ++
          (PeerId("0"), Pieces(10, Set(0, 1, 2, 3))) ++
          (PeerId("1"), Pieces(10, Set(0, 1, 2))) ++
          (PeerId("2"), Pieces(10, Set(0, 1))) ++
          (PeerId("3"), Pieces(10, Set(0)))

      mappings.rarest mustEqual Seq(
        (3, Set(PeerId("0"))),
        (2, Set(PeerId("0"), PeerId("1"))),
        (1, Set(PeerId("0"), PeerId("1"), PeerId("2"))),
        (0, Set(PeerId("0"), PeerId("1"), PeerId("2"), PeerId("3")))
      )
    }

    "rarest pieces excludes pieces we already have" in {
      val mappings =
        PeerPieceMappings(Pieces(10), Pieces(10)) ++
          (PeerId("0"), Pieces(10, Set(0, 1, 2, 3))) ++
          (PeerId("1"), Pieces(10, Set(0, 1, 2))) ++
          (PeerId("2"), Pieces(10, Set(0, 1))) ++
          (PeerId("3"), Pieces(10, Set(0))) completed 3

      mappings.rarest mustEqual Seq(
        (2, Set(PeerId("0"), PeerId("1"))),
        (1, Set(PeerId("0"), PeerId("1"), PeerId("2"))),
        (0, Set(PeerId("0"), PeerId("1"), PeerId("2"), PeerId("3")))
      )
    }
  }
}